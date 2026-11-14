package com.qingruan.agent.provider;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONException;
import com.qingruan.agent.config.AiAnalysisProperties;
import com.qingruan.agent.dto.AnalysisInput;
import com.qingruan.agent.dto.AnalysisOutput;
import com.qingruan.agent.dto.RecommendationItem;
import com.qingruan.agent.vector.SimilarCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对接本地 llama.cpp server（OpenAI 兼容 /v1/chat/completions）的告警分析实现。
 * <p>
 * 模型为 Qwen3.5 且开启了 reasoning（思维链），响应可能内嵌 {@code <think>...</think>} 块。
 * 解析时：
 * <ul>
 *   <li>优先取独立字段 {@code choices[0].message.reasoning_content} 作为推理链；</li>
 *   <li>否则用正则从 content 抽取 {@code <think>} 标签并剔除；</li>
 *   <li>剩余 content 经多层兜底（去代码围栏、截取首末花括号）解析为 JSON。</li>
 * </ul>
 * 任何解析失败均降级为空结果（recommendations 置 []），保证分析接口不抛 500。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleAnalysisProvider implements AnalysisProvider {

    /** 匹配内联思维链 <think>...</think>（含跨行，非贪婪） */
    private static final Pattern THINK_INLINE = Pattern.compile("<think>([\\s\\S]*?)</think>", Pattern.CASE_INSENSITIVE);
    /** 匹配独立 ```json 代码围栏 ``` */
    private static final Pattern FENCE = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    /** 合法 severity 取值 */
    private static final java.util.Set<String> VALID_SEVERITY =
            java.util.Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    private final RestTemplate llamaRestTemplate;
    private final AiAnalysisProperties props;

    @Override
    public AnalysisOutput analyze(AnalysisInput input, List<SimilarCase> similarCases) {
        String content = callLlama(input, similarCases);
        AnalysisOutput output = parseContent(content);
        // 解析彻底失败（既无根因也无建议）时，换一次随机性重试，提升格式稳定性
        if (isEmptyResult(output)) {
            log.warn("[AI-ANALYSIS] 首次解析无有效内容，重试一次");
            String retry = callLlama(input, similarCases);
            AnalysisOutput retryOut = parseContent(retry);
            if (!isEmptyResult(retryOut)) {
                return retryOut;
            }
        }
        return output;
    }

    /** 解析结果是否完全为空（根因和建议都缺失，视为解析失败） */
    private boolean isEmptyResult(AnalysisOutput o) {
        return o == null
                || (o.getRootCauseSummary() == null || o.getRootCauseSummary().isBlank())
                && (o.getRecommendations() == null || o.getRecommendations().isEmpty());
    }

    /**
     * 调用 llama.cpp server /v1/chat/completions。
     */
    private String callLlama(AnalysisInput input, List<SimilarCase> similarCases) {
        String url = props.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";

        JSONObject systemMsg = new JSONObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", buildSystemPrompt());

        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", buildUserPrompt(input, similarCases));

        JSONArray messages = new JSONArray();
        messages.add(systemMsg);
        messages.add(userMsg);

        JSONObject body = new JSONObject();
        body.put("model", props.getModel());
        body.put("messages", messages);
        body.put("temperature", props.getTemperature());
        body.put("stream", Boolean.FALSE);
        // JSON 模式：约束输出为合法 JSON 对象（形状由 system prompt 规定）。
        // 部分推理模型（如 deepseek-reasoner）不支持 response_format，可关闭。
        if (props.isJsonMode()) {
            JSONObject respFormat = new JSONObject();
            respFormat.put("type", "json_object");
            body.put("response_format", respFormat);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (props.getApiKey() != null && !props.getApiKey().isBlank()) {
            headers.setBearerAuth(props.getApiKey());
        }

        try {
            String resp = llamaRestTemplate.postForObject(url, new HttpEntity<>(body, headers), String.class);
            if (resp == null || resp.isBlank()) {
                log.error("[AI-ANALYSIS] llama-server 返回空响应");
                return "";
            }
            return extractContent(resp);
        } catch (Exception e) {
            log.error("[AI-ANALYSIS] 调用 llama-server 失败: {}", e.getMessage(), e);
            return "";
        }
    }

    /** 从 OpenAI 兼容响应 JSON 中取出 content 与 reasoning_content */
    private String extractContent(String respJson) {
        try {
            JSONObject root = JSON.parseObject(respJson);
            JSONArray choices = root.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                log.error("[AI-ANALYSIS] 响应缺少 choices");
                return "";
            }
            JSONObject message = choices.getJSONObject(0).getJSONObject("message");
            String content = message.getString("content");
            String reasoning = message.getString("reasoning_content");
            // 若服务把思维链放在独立字段，则把 content 与其拼接分析（reasoning 单独回填）
            return reasoning != null && !reasoning.isBlank()
                    ? "<think>" + reasoning + "</think>" + (content == null ? "" : content)
                    : (content == null ? "" : content);
        } catch (Exception e) {
            log.error("[AI-ANALYSIS] 解析响应 JSON 失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 解析模型文本为 AnalysisOutput，含思维链剥离与多层 JSON 兜底。
     */
    private AnalysisOutput parseContent(String raw) {
        AnalysisOutput output = new AnalysisOutput();
        output.setRecommendations(new ArrayList<>());

        if (raw == null || raw.isBlank()) {
            return output;
        }

        String reasoning = null;
        Matcher m = THINK_INLINE.matcher(raw);
        if (m.find()) {
            reasoning = m.group(1).trim();
            raw = raw.replace(m.group(0), "");   // 剔除思维链，保留正式内容
        }
        output.setReasoningChain(reasoning);

        String json = extractJson(raw);
        if (json == null) {
            log.error("[AI-ANALYSIS] 无法从模型输出中提取 JSON");
            return output;
        }

        try {
            JSONObject obj = JSON.parseObject(json);
            output.setRootCauseSummary(obj.getString("root_cause_summary"));

            String analysisResult = obj.getString("analysis_result");
            if (analysisResult == null || analysisResult.isBlank()) {
                // 模型偶发漏输 analysis_result：用已有字段兜底合成，保证字段不缺失
                analysisResult = buildFallbackAnalysisResult(
                        output.getRootCauseSummary(),
                        obj.getString("severity_assessment"),
                        parseRecommendations(obj.get("recommendations")));
                log.info("[AI-ANALYSIS] analysis_result 缺失，已兜底合成");
            }
            output.setAnalysisResult(analysisResult);

            String severity = obj.getString("severity_assessment");
            output.setSeverityAssessment(VALID_SEVERITY.contains(severity) ? severity : null);

            if (obj.containsKey("confidence_score") && obj.get("confidence_score") != null) {
                try {
                    output.setConfidenceScore(new BigDecimal(obj.get("confidence_score").toString()));
                } catch (NumberFormatException ignored) {
                    output.setConfidenceScore(null);
                }
            }

            List<RecommendationItem> items = parseRecommendations(obj.get("recommendations"));
            output.setRecommendations(items != null ? items : new ArrayList<>());
        } catch (JSONException e) {
            log.error("[AI-ANALYSIS] 解析分析结果 JSON 失败: {}", e.getMessage());
        }
        return output;
    }

    /** 模型漏输 analysis_result 时，用根因/级别/建议兜底合成纯文本叙述 */
    private String buildFallbackAnalysisResult(String rootCause, String severity, List<RecommendationItem> recs) {
        StringBuilder sb = new StringBuilder();
        if (rootCause != null && !rootCause.isBlank()) {
            sb.append("【根因】").append(rootCause);
        }
        if (severity != null && !severity.isBlank()) {
            sb.append("（严重程度：").append(severity).append("）");
        }
        if (recs != null && !recs.isEmpty()) {
            sb.append("\n【处置建议】\n");
            int n = 1;
            for (RecommendationItem r : recs) {
                sb.append(n++).append(". ").append(r.getTitle() == null ? "" : r.getTitle());
                if (r.getCommand() != null && !r.getCommand().isBlank()) {
                    sb.append("：").append(r.getCommand());
                }
                sb.append("\n");
            }
        }
        String fallback = sb.toString().trim();
        // 兜底保底：任何情况下都返回非空串，保证 analysis_result 字段不缺失
        return fallback.isEmpty() ? "（AI 未生成摘要，请参考根因与处置建议）" : fallback;
    }

    /** recommendations 可能为数组或字符串化的数组，统一解析为对象列表 */
    private List<RecommendationItem> parseRecommendations(Object rawRec) {
        if (rawRec == null) {
            return new ArrayList<>();
        }
        JSONArray arr;
        if (rawRec instanceof JSONArray) {
            arr = (JSONArray) rawRec;
        } else {
            try {
                arr = JSON.parseArray(rawRec.toString());
            } catch (Exception e) {
                return new ArrayList<>();
            }
        }
        List<RecommendationItem> items = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            try {
                RecommendationItem item = arr.getJSONObject(i).toJavaObject(RecommendationItem.class);
                if (item != null) {
                    items.add(item);
                }
            } catch (Exception ignored) {
                // 单条结构异常跳过，不影响整体
            }
        }
        return items;
    }

    /**
     * 从模型文本中提取 JSON 对象：先尝试直接解析，再逐层剥离代码围栏、按花括号配平截取首个完整 JSON。
     * 关键点：用括号配平（而非 lastIndexOf('}')），避免模型在 JSON 后追加废话/重复 JSON 时截出超长串导致解析失败。
     */
    private String extractJson(String text) {
        if (text == null) {
            return null;
        }
        text = text.trim();
        if (text.isEmpty()) {
            return null;
        }
        // 1) 直接就是 JSON
        if (text.startsWith("{") && text.endsWith("}")) {
            return text;
        }
        // 2) 去除 ```json ``` 围栏（取围栏内首个配平 JSON）
        Matcher fence = FENCE.matcher(text);
        if (fence.find()) {
            String inner = fence.group(1).trim();
            String balanced = extractBalanced(inner);
            if (balanced != null) {
                return balanced;
            }
        }
        // 3) 全局按花括号配平截取首个完整 JSON
        return extractBalanced(text);
    }

    /** 从首个 '{' 起做花括号配平，返回第一个配平完整的 JSON 子串；无则 null */
    private String extractBalanced(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inStr = false;
        char quote = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inStr) {
                if (c == '\\') {            // 转义字符跳过下一个
                    i++;
                    continue;
                }
                if (c == quote) {
                    inStr = false;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                inStr = true;
                quote = c;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private String buildUserPrompt(AnalysisInput input, List<SimilarCase> similarCases) {
        StringBuilder sb = new StringBuilder();
        sb.append("请分析以下告警：\n").append(userContentOf(input).toJSONString());

        if (similarCases != null && !similarCases.isEmpty()) {
            sb.append("\n\n参考以下历史相似告警的处理经验（仅供借鉴，请结合当前告警具体分析）：\n");
            int idx = 1;
            for (SimilarCase c : similarCases) {
                if (c.getDocument() == null || c.getDocument().isBlank()) {
                    continue;
                }
                sb.append(idx++).append(". ").append(c.getDocument().trim()).append("\n");
            }
        }
        return sb.toString();
    }

    private JSONObject userContentOf(AnalysisInput input) {
        JSONObject userContent = new JSONObject();
        userContent.put("alert_id", input.getAlertId());
        userContent.put("alert_type", input.getAlertType());
        userContent.put("alert_level", input.getAlertLevel());
        userContent.put("service_name", input.getServiceName());
        userContent.put("server_code", input.getServerCode());
        userContent.put("alert_content", input.getAlertContent());
        return userContent;
    }

    private String buildSystemPrompt() {
        return "你是一名资深 SRE（站点可靠性工程师）。" +
                "我会给你一条服务器运维告警的结构化上下文（JSON）。" +
                "请分析其根因并给出处置建议。\n" +
                "仅输出一个 JSON 对象，不要输出任何额外说明文字、不要使用 Markdown 代码围栏。\n" +
                "JSON 结构必须严格为：\n" +
                "{\n" +
                "  \"root_cause_summary\": \"根因总结(中文,简洁)\",\n" +
                "  \"severity_assessment\": \"LOW | MEDIUM | HIGH | CRITICAL 之一\",\n" +
                "  \"confidence_score\": 0到100之间的数字,\n" +
                "  \"recommendations\": [\n" +
                "    {\"priority\": 1, \"title\": \"建议标题\", \"steps\": [\"步骤1\",\"步骤2\"], " +
                "\"expected_effect\": \"预期效果\", \"command\": \"可选执行命令\"}\n" +
                "  ],\n" +
                "  \"reasoning_chain\": \"你的分析推理过程\",\n" +
                "  \"analysis_result\": \"根因与处置建议的纯文本叙述，供人类快速阅读，一段话概括\\\"\\n" +
                "}\\n" +
                "所有字段均为必填，缺一不可（尤其 analysis_result 必须输出，不可省略）；recommendations 至少包含一条建议；severity_assessment 必须是给定枚举之一。";
    }
}
