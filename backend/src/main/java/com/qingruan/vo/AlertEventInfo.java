package com.qingruan.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "告警事件信息")
public class AlertEventInfo {

    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "告警编号")
    private String alertNo;

    @Schema(description = "服务器唯一编码")
    private String serverCode;

    @Schema(description = "关联的监控配置ID")
    private Long configId;

    @Schema(description = "关联的自愈规则ID")
    private Long ruleId;

    @Schema(description = "告警服务名称")
    private String serviceName;

    @Schema(description = "告警类型")
    private String alertType;

    @Schema(description = "告警级别")
    private String alertLevel;

    @Schema(description = "结构化异常上下文")
    private Object alertContent;

    @Schema(description = "告警状态")
    private String alertStatus;

    @Schema(description = "客户端触发告警的时间")
    private LocalDateTime triggerTime;

    @Schema(description = "告警恢复/处理完成时间")
    private LocalDateTime resolveTime;

    @Schema(description = "记录创建时间")
    private LocalDateTime createdAt;
}
