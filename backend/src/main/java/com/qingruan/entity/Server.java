package com.qingruan.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 客户端服务器信息表（MySQL: server_info）
 * <p>
 * Agent 注册时写入元数据；心跳上报时更新 last_heartbeat / status；
 * 定时任务每 60s 扫描，将超过 60s 未上报心跳的服务器置为离线。
 */
@Data
@TableName("server_info")
public class Server implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 服务器唯一编码，Agent 需本地持久化，用于判断服务器身份 */
    private String serverCode;

    /** 主机名 */
    private String hostname;

    /** IP 地址 */
    private String ipAddress;

    /** 操作系统类型: WINDOWS / LINUX */
    private String osType;

    /** 系统详细版本号 */
    private String osVersion;

    /** CPU 信息 */
    private String cpuInfo;

    /** 内存信息 */
    private String memoryInfo;

    /** 磁盘信息 */
    private String diskInfo;

    /** Agent 客户端版本号 */
    private String agentVersion;

    /** 在线状态: 1-在线, 0-离线 */
    private Integer status;

    /** 最后一次心跳时间 */
    private LocalDateTime lastHeartbeat;

    /** 备注信息 */
    private String remark;

    /** 首次注册时间 */
    private LocalDateTime createdAt;

    /** 信息更新时间 */
    private LocalDateTime updatedAt;
}
