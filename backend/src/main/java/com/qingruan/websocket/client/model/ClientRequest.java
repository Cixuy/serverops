package com.qingruan.websocket.client.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 客户端 / 设备 → 后端 WebSocket 请求体。
 */
@Data
@Schema(description = "客户端WebSocket请求")
public class ClientRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 动作：PULL_MONITOR_CONFIG / ping 等 */
    private String action;

    @JsonProperty("request_id")
    private String requestId;

    /** 业务负载 */
    private Payload payload;

    @Data
    @Schema(description = "客户端请求负载")
    public static class Payload implements Serializable {
        /** 服务器唯一编码 */
        @JsonProperty("server_code")
        private String serverCode;
    }
}
