package com.qingruan.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Agent 心跳上报请求
 */
@Data
public class HeartbeatRequest {

    /** 服务器唯一编码 */
    @NotBlank(message = "serverCode不能为空")
    private String serverCode;

    /** 客户端时间戳(ms) */
    private Long timestamp;
}
