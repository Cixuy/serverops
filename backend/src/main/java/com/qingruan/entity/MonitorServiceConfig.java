package com.qingruan.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 监控服务配置表（MySQL: monitor_service_config）
 * <p>
 * 服务端下发给 Agent 的监控任务配置（进程/端口存活探测）。
 * Agent 启动或定时拉取后，按 check_interval 周期性探测并上报。
 */
@Data
@TableName("monitor_service_config")
@Schema(description = "监控服务配置")
public class MonitorServiceConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    @Schema(description = "主键ID")
    private Long id;

    private String serverCode;

    @Schema(description = "服务名称（如：Nginx, MySQL, 业务后端）")
    private String serviceName;

    private String processName;

    /** 监听端口号 */
    private Integer port;

    /** 探测频率（秒） */
    private Integer checkInterval;

    @Schema(description = "配置状态：1-启用 / 0-停用")
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
