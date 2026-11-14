package com.qingruan.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * Agent 上报服务状态请求体
 */
@Data
@Schema(description = "服务状态上报请求")
public class ServiceStatusReportRequest {

    @Schema(description = "服务器唯一编码", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "serverCode 不能为空")
    private String serverCode;

    @Schema(description = "服务状态列表", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "services 不能为空")
    @Valid
    private List<ServiceStatusItem> services;

    @Data
    @Schema(description = "单个服务状态项")
    public static class ServiceStatusItem {

        @Schema(description = "监控配置ID", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "configId 不能为空")
        private Long configId;

        @Schema(description = "服务名称", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "serviceName 不能为空")
        private String serviceName;

        @Schema(description = "是否存活: 1-正常 0-异常", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "isAlive 不能为空")
        private Integer isAlive;

        @Schema(description = "当前进程PID（若存活）")
        private Integer pid;

        @Schema(description = "端口状态: 1-监听中 0-未监听")
        private Integer portStatus;

        @Schema(description = "最后一次探测时间（毫秒时间戳）", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "lastCheckTime 不能为空")
        private Long lastCheckTime;

        @Schema(description = "告警上下文/异常信息")
        private String alertContext;
    }
}
