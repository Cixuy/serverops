package com.qingruan.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "告警处置操作流水信息")
public class AlertActionLogInfo {

    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "关联告警事件ID")
    private Long alertId;

    @Schema(description = "操作类型")
    private String actionType;

    @Schema(description = "操作人ID")
    private Long operatorId;

    @Schema(description = "操作人姓名")
    private String operatorName;

    @Schema(description = "执行结果")
    private String actionResult;

    @Schema(description = "操作详情")
    private String actionDetail;

    @Schema(description = "操作时间")
    private LocalDateTime createdAt;
}
