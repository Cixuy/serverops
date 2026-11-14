package com.qingruan.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Agent 注册请求
 */
@Data
public class ServerRegisterRequest {

    /** 主机名 */
    @NotBlank(message = "主机名不能为空")
    private String hostname;

    /** IP地址 */
    @NotBlank(message = "IP地址不能为空")
    private String ipAddress;

    /** 操作系统类型: WINDOWS / LINUX */
    @NotBlank(message = "操作系统类型不能为空")
    private String osType;

    /** 系统详细版本号 */
    private String osVersion;

    /** CPU信息 */
    private String cpuInfo;

    /** 内存信息 */
    private String memoryInfo;

    /** 磁盘信息 */
    private String diskInfo;

    /** Agent客户端版本号 */
    @NotBlank(message = "Agent版本号不能为空")
    private String agentVersion;
}
