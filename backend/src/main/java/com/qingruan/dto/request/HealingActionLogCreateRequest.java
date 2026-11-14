package com.qingruan.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 管理端新增/补录自愈操作日志请求
 * 请求字段使用驼峰命名（camelCase），与接口文档示例保持一致
 */
@Data
@Schema(description = "新增自愈操作日志请求")
public class HealingActionLogCreateRequest {

    @Schema(description = "关联告警事件ID（可为空）", example = "10086")
    private Long alertId;

    @Schema(description = "执行服务器编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "SRV-8F3A2B")
    @NotBlank(message = "serverCode不能为空")
    private String serverCode;

    @Schema(description = "监控配置ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "1001")
    @NotNull(message = "configId不能为空")
    private Long configId;

    @Schema(description = "触发的自愈规则ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "5")
    @NotNull(message = "ruleId不能为空")
    private Long ruleId;

    @Schema(description = "服务名称（冗余字段，方便查询展示）", requiredMode = Schema.RequiredMode.REQUIRED, example = "Nginx")
    @NotBlank(message = "serviceName不能为空")
    private String serviceName;

    @Schema(description = "触发时的上下文数据快照(JSON)", example = "{\"error_msg\":\"Connection refused\"}")
    private Object triggerDetail;

    @Schema(description = "实际执行命令快照", requiredMode = Schema.RequiredMode.REQUIRED, example = "systemctl restart nginx")
    @NotBlank(message = "executeCommand不能为空")
    private String executeCommand;

    @Schema(description = "命令标准输出/错误输出", example = "nginx restarted successfully")
    private String commandOutput;

    @Schema(description = "执行状态：SUCCESS / FAILED / TIMEOUT / RETRYING", requiredMode = Schema.RequiredMode.REQUIRED, example = "SUCCESS")
    @NotBlank(message = "executionStatus不能为空")
    @Pattern(regexp = "SUCCESS|FAILED|TIMEOUT|RETRYING", message = "executionStatus非法，应为 SUCCESS / FAILED / TIMEOUT / RETRYING")
    private String executionStatus;

    @Schema(description = "当前重试次数（从0开始）", example = "0")
    private Integer retryCount;

    @Schema(description = "开始执行时间", requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-08-24T10:30:00+08:00")
    @NotNull(message = "startedAt不能为空")
    private OffsetDateTime startedAt;

    @Schema(description = "执行结束时间", example = "2026-08-24T10:30:05+08:00")
    private OffsetDateTime finishedAt;

    @Schema(description = "执行耗时（毫秒）", example = "5000")
    private Integer durationMs;
}
