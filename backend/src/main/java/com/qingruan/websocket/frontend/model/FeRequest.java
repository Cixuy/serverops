package com.qingruan.websocket.frontend.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 前端 → 后端 WebSocket 请求体。
 * <p>
 * 字段命名与通信协议保持一致（蛇形），通过 @JsonProperty 完成映射。
 */
@Data
@Schema(description = "前端WebSocket请求")
public class FeRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 动作：subscribe / unsubscribe / ping */
    private String action;

    @JsonProperty("request_id")
    private String requestId;

    /** 业务负载 */
    private Payload payload;

    @Data
    @Schema(description = "前端请求负载")
    public static class Payload implements Serializable {
        /** 订阅的服务器编码列表 */
        @JsonProperty("server_codes")
        private List<String> serverCodes;

        /** 订阅的指标类型列表（server_metrics / server_disk_metrics） */
        @JsonProperty("metrics")
        private List<String> metrics;
    }
}
