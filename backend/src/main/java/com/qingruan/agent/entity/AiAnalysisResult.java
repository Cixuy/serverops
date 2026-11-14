package com.qingruan.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 告警分析结果表（MySQL: ai_analysis_result）
 * <p>
 * recommendations 为 MySQL JSON 列，实体中以字符串存储（入库序列化、出参反序列化），
 * 与现有 alert_event.alert_content 保持一致的写法。
 */
@Data
@TableName("ai_analysis_result")
@Schema(description = "AI告警分析结果")
public class AiAnalysisResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    @Schema(description = "主键ID")
    private Long id;

    /** 关联 ai_analysis_task.id（当前阶段未启用，预留为可空） */
    @Schema(description = "关联分析任务ID（预留）")
    private Long taskId;

    /** 关联 alert_event.id */
    @Schema(description = "关联告警事件ID")
    private Long alertId;

    /** AI总结的根因描述 */
    @Schema(description = "根因总结")
    private String rootCauseSummary;

    /** AI评估的严重程度：LOW / MEDIUM / HIGH / CRITICAL */
    @Schema(description = "严重程度评估")
    private String severityAssessment;

    /** AI分析置信度（0.00 ~ 100.00） */
    @Schema(description = "置信度(0.00~100.00)")
    private BigDecimal confidenceScore;

    /** AI处置建议列表（结构化 JSON 字符串） */
    @Schema(description = "处置建议列表(JSON)")
    private String recommendations;

    /** AI推理链/思维过程（对应 Qwen 思维链，用于可解释性展示） */
    @Schema(description = "推理链/思维过程")
    private String reasoningChain;

    /** 根因+建议汇总的纯文本叙述（给人阅读，区别于结构化字段） */
    @Schema(description = "分析结果叙述(根因+建议汇总)")
    private String analysisResult;

    /** 用户反馈：ACCURATE / INACCURATE / PARTIAL */
    @Schema(description = "用户反馈")
    private String userFeedback;

    /** 用户反馈备注 */
    @Schema(description = "反馈备注")
    private String feedbackComment;

    /** 创建时间 */
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
