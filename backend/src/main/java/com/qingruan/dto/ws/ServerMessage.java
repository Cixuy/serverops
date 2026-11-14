package com.qingruan.dto.ws;

import cn.hutool.core.annotation.Alias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 服务端 → 前端 WebSocket 消息
 * <p>
 * type 取值: ack / error / server_metrics / server_disk_metrics
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServerMessage {

    /** 消息类型: ack / error / server_metrics / server_disk_metrics */
    private String type;

    /** 与请求关联的 requestId（error / ack 时携带） */
    private String requestId;

    /** 业务编码（error 时） */
    private String code;

    /** 提示信息（ack / error 时） */
    private String message;

    /** 状态（ack 时: ok / failed） */
    private Object status;

    @Alias("action_type")
    @JsonProperty("action_type")
    private String actionType;

    /** 服务器编码（指标推送时） */
    private String serverCode;

    /** 采集时间戳（ISO-8601 字符串，如 2026-08-21T14:45:00.000Z） */
    private String timestamp;

    /** 基础指标数据（type=server_metrics 时） */
    private Map<String, Object> data;

    /** 磁盘指标数据列表（type=server_disk_metrics 时） */
    private List<Map<String, Object>> dataList;
}
