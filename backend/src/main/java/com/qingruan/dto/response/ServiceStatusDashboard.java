package com.qingruan.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 服务状态看板统计
 */
@Data
@Schema(description = "服务状态看板统计")
public class ServiceStatusDashboard implements Serializable {

    @Schema(description = "服务总数")
    private Integer totalServices;

    @Schema(description = "正常存活服务数")
    private Integer aliveServices;

    @Schema(description = "异常/离线服务数")
    private Integer downServices;

    @Schema(description = "服务器唯一编码（不传则统计全局）")
    private String serverCode;
}
