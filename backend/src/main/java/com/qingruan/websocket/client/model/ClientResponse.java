package com.qingruan.websocket.client.model;

import cn.hutool.core.annotation.Alias;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 后端 → 客户端 / 设备 WebSocket 响应体。
 * <p>
 * 正常响应示例：
 * {"action":"PULL_MONITOR_CONFIG_RESP","request_id":"...","data":[...]}
 * 错误响应示例：
 * {"action":"error","request_id":"...","code":"SERVER_NOT_FOUND","message":"..."}
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "客户端WebSocket响应")
public class ClientResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 动作：PULL_MONITOR_CONFIG_RESP / error / pong */
    private String action;

    /** 接收配置变更通知的服务器唯一编码 */
    @Alias("server_code")
    @JsonProperty("server_code")
    private String serverCode;

    /** 配置变更状态：1-启用，0-未启用 */
    private Integer status;

    /** 配置变更动作：POST / PUT / DEL */
    @Alias("action_type")
    @JsonProperty("action_type")
    private String actionType;

    @JsonProperty("request_id")
    private String requestId;

    /** 业务码：仅错误时使用，如 SERVER_NOT_FOUND */
    private String code;

    /** 提示信息：仅错误时使用 */
    private String message;

    /** 业务数据：正常响应时为监控配置列表 */
    private List<ClientMonitorConfig> data;
}
