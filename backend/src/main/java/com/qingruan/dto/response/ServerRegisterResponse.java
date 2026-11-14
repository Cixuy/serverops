package com.qingruan.dto.response;

import lombok.Data;

/**
 * Agent 注册响应
 */
@Data
public class ServerRegisterResponse {

    /** 服务器唯一编码，Agent需本地持久化 */
    private String serverCode;

    /** 服务器标识ID（MySQL server 表自增主键） */
    private Long serverId;
}
