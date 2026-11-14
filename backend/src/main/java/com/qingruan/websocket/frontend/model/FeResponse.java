package com.qingruan.websocket.frontend.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 后端 → 前端 WebSocket 响应体。
 * <p>
 * 注意：基础指标（server_metrics）与磁盘指标（server_disk_metrics）统一使用
 * {@code data} 字段承载——前者为 JSON 对象，后者为 JSON 数组。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "前端WebSocket响应")
public class FeResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 消息类型：ack / error / pong / server_metrics / server_disk_metrics */
    private String type;

    @JsonProperty("request_id")
    private String requestId;

    /** 状态：ok / fail */
    private String status;

    /** 业务码：如 INVALID_SERVER_CODE */
    private String code;

    /** 提示信息 */
    private String message;

    @JsonProperty("server_code")
    private String serverCode;

    /** 时间戳（ISO-8601，UTC，如 2026-08-21T14:45:00.000Z） */
    private String timestamp;

    /** 业务数据：基础指标为对象，磁盘指标为数组 */
    private Object data;
}
