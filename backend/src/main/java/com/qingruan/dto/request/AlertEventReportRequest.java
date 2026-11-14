package com.qingruan.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * Agent/监控组件上报告警事件请求
 * 请求字段使用下划线命名（snake_case），通过 @JsonNaming 自动映射为驼峰属性
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Schema(description = "上报告警事件请求")
public class AlertEventReportRequest {

    @Schema(description = "发生告警的服务器唯一编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "SRV-8F3A2B")
    @NotBlank(message = "serverCode不能为空")
    private String serverCode;

    @Schema(description = "关联的监控服务配置ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "1001")
    @NotNull(message = "configId不能为空")
    private Long configId;

    @Schema(description = "关联的自愈规则ID（非强制，可为空）", example = "1001")
    private Long ruleId;

    @Schema(description = "告警服务名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "Nginx")
    @NotBlank(message = "serviceName不能为空")
    private String serviceName;

    @Schema(description = "告警类型：SERVICE_DOWN / SERVICE_DEGRADED / CONFIG_ERROR", requiredMode = Schema.RequiredMode.REQUIRED, example = "SERVICE_DOWN")
    @NotBlank(message = "alertType不能为空")
    @Pattern(regexp = "SERVICE_DOWN|SERVICE_DEGRADED|CONFIG_ERROR", message = "alertType非法，应为 SERVICE_DOWN / SERVICE_DEGRADED / CONFIG_ERROR")
    private String alertType;

    @Schema(description = "告警级别：INFO / WARNING / CRITICAL，不传则取系统默认 CRITICAL", example = "CRITICAL")
    @Pattern(regexp = "INFO|WARNING|CRITICAL", message = "alertLevel非法，应为 INFO / WARNING / CRITICAL")
    private String alertLevel;

    @Schema(description = "结构化异常上下文（如错误日志、指标快照）", requiredMode = Schema.RequiredMode.REQUIRED, example = "{\"error_msg\":\"Connection refused\"}")
    @NotNull(message = "alertContent不能为空")
    private Object alertContent;

    @Schema(description = "客户端检测到异常触发的时间(ISO8601，可带时区偏移)", requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-08-24T10:30:00+08:00")
    @NotNull(message = "triggerTime不能为空")
    private OffsetDateTime triggerTime;
}
