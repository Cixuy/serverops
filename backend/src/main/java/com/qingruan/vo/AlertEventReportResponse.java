package com.qingruan.vo;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 上报告警事件响应（下划线命名，与请求保持一致）
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "上报告警事件响应")
public class AlertEventReportResponse {

    @Schema(description = "告警事件主键ID", example = "10086")
    private Long id;

    @Schema(description = "告警编号", example = "ALM202608240001")
    private String alertNo;

    @Schema(description = "告警状态：PENDING / HEALING / RESOLVED / IGNORED", example = "RESOLVED")
    private String alertStatus;
}
