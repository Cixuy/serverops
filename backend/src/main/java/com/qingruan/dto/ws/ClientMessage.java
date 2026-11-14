package com.qingruan.dto.ws;

import cn.hutool.core.annotation.Alias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 前端 → 服务端 WebSocket 消息（订阅 / 取消订阅 / 心跳）
 */
@Data
public class ClientMessage {

    /** subscribe / unsubscribe / ping */
    private String action;

    @Alias("action_type")
    @JsonProperty("action_type")
    private String actionType;

    private Integer status;

    /** 请求ID（可选，原样回传用于 ack / error 关联） */
    private String requestId;

    /** 时间戳(ms)，ping 时携带 */
    private Long timestamp;

    /** 业务载荷 */
    private Payload payload;

    @Data
    public static class Payload {
        /** 订阅的服务器编码列表 */
        private List<String> serverCodes;

        /** 订阅的指标类型: server_metrics / server_disk_metrics */
        private List<String> metrics;
    }
}
