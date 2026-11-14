package com.qingruan.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * Agent 拉取的监控配置项
 */
@Data
@Schema(description = "Agent监控配置")
public class AgentMonitorConfig implements Serializable {

    @Schema(description = "配置ID")
    private Long configId;

    @Schema(description = "服务名称")
    private String serviceName;

    @Schema(description = "进程名称或关键字")
    private String processName;

    @Schema(description = "监听端口号")
    private Integer port;

    @Schema(description = "探测频率（秒）")
    private Integer checkInterval;
}
