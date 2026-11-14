package com.qingruan.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 服务状态快照信息（看板查询返回）
 */
@Data
@Schema(description = "服务状态快照信息")
public class ServiceStatusSnapshotInfo implements Serializable {

    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "服务器唯一编码")
    private String serverCode;

    @Schema(description = "关联监控配置ID")
    private Long configId;

    @Schema(description = "服务名称")
    private String serviceName;

    @Schema(description = "当前状态: 1-正常存活 / 0-异常离线")
    private Integer isAlive;

    @Schema(description = "当前进程PID")
    private Integer pid;

    @Schema(description = "端口状态: 1-监听中 / 0-未监听")
    private Integer portStatus;

    @Schema(description = "客户端最后一次探测时间")
    private LocalDateTime lastCheckTime;

    @Schema(description = "服务端接收更新时间")
    private LocalDateTime updatedAt;
}
