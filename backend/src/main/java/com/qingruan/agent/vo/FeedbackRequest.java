package com.qingruan.agent.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.Pattern;

/**
 * 用户反馈请求：对 AI 分析结果给出准确性评价。
 */
@Data
@Schema(description = "AI分析反馈请求")
public class FeedbackRequest {

    @Schema(description = "反馈: ACCURATE / INACCURATE / PARTIAL")
    @Pattern(regexp = "ACCURATE|INACCURATE|PARTIAL", message = "userFeedback 必须为 ACCURATE / INACCURATE / PARTIAL")
    private String userFeedback;

    @Schema(description = "反馈备注")
    private String feedbackComment;
}
