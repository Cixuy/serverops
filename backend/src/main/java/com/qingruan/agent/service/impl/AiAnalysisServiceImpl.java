package com.qingruan.agent.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qingruan.agent.dto.AnalysisInput;
import com.qingruan.agent.dto.AnalysisOutput;
import com.qingruan.agent.dto.RecommendationItem;
import com.qingruan.agent.entity.AiAnalysisResult;
import com.qingruan.agent.mapper.AiAnalysisResultMapper;
import com.qingruan.agent.provider.AnalysisProvider;
import com.qingruan.agent.service.AiAnalysisService;
import com.qingruan.agent.config.VectorProperties;
import com.qingruan.agent.service.EmbeddingService;
import com.qingruan.agent.vector.SimilarCase;
import com.qingruan.agent.vector.VectorStore;
import com.qingruan.agent.vo.AiAnalysisResultVO;
import com.qingruan.agent.vo.FeedbackRequest;
import com.qingruan.common.constant.SystemCodeEnum;
import com.qingruan.common.exception.BizException;
import com.qingruan.entity.AlertEvent;
import com.qingruan.mapper.AlertEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 告警 AI 分析业务实现。
 * <p>
 * 手动触发：查告警 → 调 AnalysisProvider → 落库 ai_analysis_result。
 * 不改动 alert_event，与自愈链路解耦。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAnalysisServiceImpl implements AiAnalysisService {

    private final AiAnalysisResultMapper resultMapper;
    private final AlertEventMapper alertEventMapper;
    private final AnalysisProvider analysisProvider;
    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;
    private final VectorProperties vectorProperties;

    @Override
    public AiAnalysisResultVO analyze(Long alertId) {
        // 幂等：每个告警仅分析一次。已存在分析结果则直接返回，不再调用 LLM
        AiAnalysisResult existing = resultMapper.selectOne(
                new LambdaQueryWrapper<AiAnalysisResult>().eq(AiAnalysisResult::getAlertId, alertId));
        if (existing != null) {
            log.info("[AI-ANALYSIS] 告警已分析过，直接返回已有结果 alertId={}, resultId={}",
                    alertId, existing.getId());
            return toVO(existing);
        }

        AlertEvent alert = alertEventMapper.selectById(alertId);
        if (alert == null) {
            throw new BizException(SystemCodeEnum.NOT_FOUND);
        }

        AnalysisInput input = new AnalysisInput();
        input.setAlertId(alert.getId());
        input.setAlertType(alert.getAlertType());
        input.setAlertLevel(alert.getAlertLevel());
        input.setServiceName(alert.getServiceName());
        input.setServerCode(alert.getServerCode());
        input.setAlertContent(parseContent(alert.getAlertContent()));

        // ② 向量检索相似历史告警（RAG 上下文），失败则降级为空，不阻断主流程
        List<SimilarCase> similarCases = retrieveSimilar(alert);

        log.info("[AI-ANALYSIS] 开始分析告警 alertId={}", alertId);
        AnalysisOutput out = analysisProvider.analyze(input, similarCases);

        AiAnalysisResult entity = new AiAnalysisResult();
        entity.setAlertId(alertId);
        entity.setRootCauseSummary(out.getRootCauseSummary());
        entity.setSeverityAssessment(out.getSeverityAssessment());
        entity.setConfidenceScore(out.getConfidenceScore());
        // recommendations 序列化为 JSON 字符串落库（保证非空，兜底 []）
        entity.setRecommendations(JSON.toJSONString(
                out.getRecommendations() == null ? List.of() : out.getRecommendations()));
        entity.setReasoningChain(out.getReasoningChain());
        // 分析结果叙述：优先取模型产出；模型未返回时由「根因 + 建议列表」兜底合成
        entity.setAnalysisResult(composeAnalysisResult(out));
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        try {
            resultMapper.insert(entity);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发场景下两个请求同时通过判空，唯一索引兜底：返回已存在的那条结果
            log.info("[AI-ANALYSIS] 并发重复分析，唯一索引拦截 alertId={}", alertId);
            AiAnalysisResult dup = resultMapper.selectOne(
                    new LambdaQueryWrapper<AiAnalysisResult>().eq(AiAnalysisResult::getAlertId, alertId));
            if (dup != null) {
                return toVO(dup);
            }
            throw e;
        }

        log.info("[AI-ANALYSIS] 告警分析完成 alertId={}, resultId={}", alertId, entity.getId());
        // ⑥ 分析成功后把本条索引进向量库（语料自增长；analyze 每告警仅一次，索引也天然幂等）
        indexAlert(alertId);
        return toVO(entity);
    }

    @Override
    public boolean isVectorAvailable() {
        return vectorStore.available();
    }

    /** 公开：将某条告警索引进向量库（供历史回填调用；分析成功后也会自动调用） */
    @Override
    public boolean indexAlert(Long alertId) {
        if (!vectorStore.available()) {
            return false;
        }
        AlertEvent alert = alertEventMapper.selectById(alertId);
        if (alert == null) {
            return false;
        }
        // 取该告警已有的分析结果（含用户反馈），拼入索引文本；无则仅索引告警本身
        AiAnalysisResult result = resultMapper.selectOne(
                new LambdaQueryWrapper<AiAnalysisResult>().eq(AiAnalysisResult::getAlertId, alertId));
        String text = buildIndexText(alert, result);
        float[] vec = embeddingService.embed(text);
        if (vec == null) {
            return false;
        }
        java.util.Map<String, Object> meta = new java.util.HashMap<>();
        meta.put("alert_id", alertId);
        meta.put("alert_type", alert.getAlertType());
        // 把用户反馈写入 metadata，供检索端做质量过滤
        if (result != null && result.getUserFeedback() != null && !result.getUserFeedback().isBlank()) {
            meta.put("user_feedback", result.getUserFeedback());
        }
        return vectorStore.upsert(String.valueOf(alertId), vec, text, meta);
    }

    /** 向量检索相似历史告警，作为参考上下文注入 prompt */
    private List<SimilarCase> retrieveSimilar(AlertEvent alert) {
        if (!vectorStore.available()) {
            return List.of();
        }
        String text = buildIndexText(alert, null);
        float[] vec = embeddingService.embed(text);
        if (vec == null) {
            return List.of();
        }
        int topK = vectorProperties.getChroma().getTopK();
        // 多召回若干条，便于剔除低质量案例后仍补足 topK
        int recall = topK * 2 + 1;
        List<SimilarCase> hits = vectorStore.search(vec, recall, String.valueOf(alert.getId()));
        if (hits.isEmpty()) {
            return List.of();
        }
        // 按反馈质量过滤排序：优先准确保留，剔除不准确，其余中性案例居中
        List<SimilarCase> accurate = new ArrayList<>();
        List<SimilarCase> neutral = new ArrayList<>();
        for (SimilarCase c : hits) {
            Object fb = c.getMetadata() == null ? null : c.getMetadata().get("user_feedback");
            String feedback = fb == null ? null : String.valueOf(fb);
            if ("INACCURATE".equalsIgnoreCase(feedback)) {
                continue; // 直接排除低质量历史案例，避免误导模型
            } else if ("ACCURATE".equalsIgnoreCase(feedback)) {
                accurate.add(c);
            } else {
                neutral.add(c);
            }
        }
        List<SimilarCase> ranked = new ArrayList<>();
        ranked.addAll(accurate);
        ranked.addAll(neutral);
        return ranked.size() > topK ? ranked.subList(0, topK) : ranked;
    }

    /** 拼接待索引/检索的归一化文本：类型+级别+服务+结构化content（+可选历史根因/建议） */
    private String buildIndexText(AlertEvent alert, AiAnalysisResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("告警类型: ").append(alert.getAlertType() == null ? "" : alert.getAlertType()).append("\n");
        sb.append("告警级别: ").append(alert.getAlertLevel() == null ? "" : alert.getAlertLevel()).append("\n");
        sb.append("服务: ").append(alert.getServiceName() == null ? "" : alert.getServiceName()).append("\n");
        sb.append("服务器: ").append(alert.getServerCode() == null ? "" : alert.getServerCode()).append("\n");
        if (alert.getAlertContent() != null) {
            sb.append("详情: ").append(alert.getAlertContent()).append("\n");
        }
        if (result != null) {
            if (result.getRootCauseSummary() != null) {
                sb.append("历史根因: ").append(result.getRootCauseSummary()).append("\n");
            }
            if (result.getAnalysisResult() != null) {
                sb.append("历史处置: ").append(result.getAnalysisResult()).append("\n");
            }
            // 用户反馈闭环进 RAG：把准确性评价拼入索引文本，使低质量历史案例可被语义区分
            if (result.getUserFeedback() != null && !result.getUserFeedback().isBlank()) {
                sb.append("用户反馈: ").append(result.getUserFeedback());
                if (result.getFeedbackComment() != null && !result.getFeedbackComment().isBlank()) {
                    sb.append("（备注: ").append(result.getFeedbackComment()).append("）");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /** alert_content 入库时为 JSON 字符串，转为对象更利于模型理解 */
    private Object parseContent(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return JSON.parse(raw);
        } catch (Exception e) {
            return raw;
        }
    }

    /**
     * 组装「分析结果叙述」：模型已返回则直接用，否则用根因 + 建议列表兜底合成一段人读文本。
     */
    private String composeAnalysisResult(AnalysisOutput out) {
        if (out.getAnalysisResult() != null && !out.getAnalysisResult().isBlank()) {
            return out.getAnalysisResult();
        }
        StringBuilder sb = new StringBuilder();
        if (out.getRootCauseSummary() != null && !out.getRootCauseSummary().isBlank()) {
            sb.append("【根因】").append(out.getRootCauseSummary());
        }
        List<RecommendationItem> recs = out.getRecommendations();
        if (recs != null && !recs.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append("【处置建议】");
            for (int i = 0; i < recs.size(); i++) {
                RecommendationItem r = recs.get(i);
                sb.append("\n").append(i + 1).append(". ");
                if (r.getTitle() != null) {
                    sb.append(r.getTitle()).append("：");
                }
                if (r.getSteps() != null && !r.getSteps().isEmpty()) {
                    sb.append(String.join(" → ", r.getSteps()));
                }
                if (r.getExpectedEffect() != null) {
                    sb.append("（预期：").append(r.getExpectedEffect()).append("）");
                }
            }
        }
        String result = sb.toString();
        return result.isBlank() ? null : result;
    }

    @Override
    public AiAnalysisResultVO getByAlert(Long alertId) {
        // 每个告警仅一条分析结果：返回单条或 null
        AiAnalysisResult entity = resultMapper.selectOne(
                new LambdaQueryWrapper<AiAnalysisResult>()
                        .eq(AiAnalysisResult::getAlertId, alertId)
                        .orderByDesc(AiAnalysisResult::getCreatedAt)
                        .last("LIMIT 1"));
        return entity == null ? null : toVO(entity);
    }

    @Override
    public void saveFeedback(Long id, FeedbackRequest req) {
        AiAnalysisResult entity = resultMapper.selectById(id);
        if (entity == null) {
            throw new BizException(SystemCodeEnum.NOT_FOUND);
        }
        entity.setUserFeedback(req.getUserFeedback());
        entity.setFeedbackComment(req.getFeedbackComment());
        entity.setUpdatedAt(LocalDateTime.now());
        resultMapper.updateById(entity);
        // 反馈闭环进 RAG：用反馈后的结果重建该告警向量（覆盖同 id 旧向量，幂等）
        indexAlert(entity.getAlertId());
    }

    /** 实体 → 对外视图（recommendations 反序列化为对象列表） */
    private AiAnalysisResultVO toVO(AiAnalysisResult entity) {
        AiAnalysisResultVO vo = new AiAnalysisResultVO();
        vo.setId(entity.getId());
        vo.setAlertId(entity.getAlertId());
        vo.setRootCauseSummary(entity.getRootCauseSummary());
        vo.setSeverityAssessment(entity.getSeverityAssessment());
        vo.setConfidenceScore(entity.getConfidenceScore());
        vo.setReasoningChain(entity.getReasoningChain());
        vo.setAnalysisResult(entity.getAnalysisResult());
        vo.setUserFeedback(entity.getUserFeedback());
        vo.setFeedbackComment(entity.getFeedbackComment());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());

        List<RecommendationItem> recs = null;
        if (entity.getRecommendations() != null && !entity.getRecommendations().isBlank()) {
            try {
                recs = JSON.parseArray(entity.getRecommendations(), RecommendationItem.class);
            } catch (Exception e) {
                log.warn("[AI-ANALYSIS] recommendations 反序列化失败 id={}: {}", entity.getId(), e.getMessage());
            }
        }
        vo.setRecommendations(recs);
        return vo;
    }
}
