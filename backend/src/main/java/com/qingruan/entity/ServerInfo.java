package com.qingruan.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("server_info")
@Schema(description = "服务器信息")
public class ServerInfo {

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "服务器唯一编码")
    private String serverCode;

    @Schema(description = "主机名")
    private String hostname;

    @Schema(description = "IP地址")
    private String ipAddress;

    @Schema(description = "操作系统类型：WINDOWS / LINUX")
    private String osType;

    @Schema(description = "系统详细版本号")
    private String osVersion;

    @Schema(description = "CPU信息")
    private String cpuInfo;

    @Schema(description = "内存信息")
    private String memoryInfo;

    @Schema(description = "磁盘信息")
    private String diskInfo;

    @Schema(description = "Agent客户端版本号")
    private String agentVersion;

    @Schema(description = "在线状态：1-在线 0-离线")
    private Integer status;

    @Schema(description = "最后一次心跳时间")
    private LocalDateTime lastHeartbeat;

    @Schema(description = "备注信息")
    private String remark;

    @Schema(description = "首次注册时间")
    private LocalDateTime createdAt;

    @Schema(description = "信息更新时间")
    private LocalDateTime updatedAt;
}
