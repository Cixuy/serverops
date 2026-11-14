package com.qingruan.agent.vo;

import com.qingruan.agent.dto.RecommendationItem;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AI分析结果对外视图。
 * <p>
 * recommendations 字段反序列化为对象列表（入库时为 JSON 字符串，出参解析）。
 */
@Data
@Schema(description = "AI告警分析结果视图")
public class AiAnalysisResultVO {

    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "关联告警事件ID")
    private Long alertId;

    @Schema(description = "根因总结")
    private String rootCauseSummary;

    @Schema(description = "严重程度: LOW/MEDIUM/HIGH/CRITICAL")
    private String severityAssessment;

    @Schema(description = "置信度(0.00~100.00)")
    private BigDecimal confidenceScore;

    @Schema(description = "处置建议列表")
    private List<RecommendationItem> recommendations;

    @Schema(description = "推理链/思维过程")
    private String reasoningChain;

    @Schema(description = "分析结果叙述(根因+建议汇总)")
    private String analysisResult;

    @Schema(description = "用户反馈: ACCURATE/INACCURATE/PARTIAL")
    private String userFeedback;

    @Schema(description = "反馈备注")
    private String feedbackComment;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
