package com.qingruan.agent.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 分析出参：LLM provider 解析后的结构化结果。
 * <p>
 * 约定：recommendations 绝不为 null（provider 兜底置空数组），避免落库 NOT NULL 约束失败。
 */
@Data
public class AnalysisOutput implements Serializable {

    /** 根因总结 */
    private String rootCauseSummary;

    /** 严重程度：LOW / MEDIUM / HIGH / CRITICAL（非法值由 provider 置 null） */
    private String severityAssessment;

    /** 置信度 0.00 ~ 100.00 */
    private BigDecimal confidenceScore;

    /** 处置建议列表（必非空） */
    private List<RecommendationItem> recommendations;

    /** 推理链 / 思维过程（来自模型 thinking 块） */
    private String reasoningChain;

    /** 根因+建议汇总的纯文本叙述（给人阅读，区别于结构化字段；模型未返回时由 service 兜底合成） */
    private String analysisResult;
}
