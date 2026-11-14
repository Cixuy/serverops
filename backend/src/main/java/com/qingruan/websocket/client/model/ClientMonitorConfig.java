package com.qingruan.websocket.client.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 下发给客户端的监控配置项（蛇形命名字段，与通信协议一致）。
 */
@Data
@Schema(description = "客户端监控配置项")
public class ClientMonitorConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    @JsonProperty("config_id")
    private Long configId;

    @JsonProperty("service_name")
    private String serviceName;

    @JsonProperty("process_name")
    private String processName;

    @JsonProperty("port")
    private Integer port;

    @JsonProperty("check_interval")
    private Integer checkInterval;
}
