package com.qingruan.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 服务状态快照表（MySQL: service_status_snapshot）
 * <p>
 * 只存每个监控配置最新一次状态（按 server_code + config_id 唯一）。
 * Agent 上报后 upsert，便于看板直接查询当前状态。
 */
@Data
@TableName("service_status_snapshot")
public class ServiceStatusSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 服务器唯一编码 */
    private String serverCode;

    /** 关联 monitor_service_config.id */
    private Long configId;

    /** 冗余服务名称，方便查询展示 */
    private String serviceName;

    /** 当前状态：1-正常存活 / 0-异常/离线 */
    private Integer isAlive;

    /** 当前进程PID（若存活） */
    private Integer pid;

    /** 端口状态：1-监听中 / 0-未监听 */
    private Integer portStatus;

    /** 客户端最后一次探测时间 */
    private LocalDateTime lastCheckTime;

    /** 服务端接收更新时间 */
    private LocalDateTime updatedAt;
}
