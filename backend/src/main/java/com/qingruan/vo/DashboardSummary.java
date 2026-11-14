package com.qingruan.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
@Schema(description = "大屏统计概览")
public class DashboardSummary implements Serializable {

    @Schema(description = "服务器总数", example = "20")
    private long totalServers;

    @Schema(description = "在线服务器数", example = "18")
    private long onlineServers;

    @Schema(description = "离线服务器数", example = "2")
    private long offlineServers;

    @Schema(description = "今日告警总数(迭代二启用)", example = "0")
    private long totalAlertsToday;

    @Schema(description = "今日自愈总数(迭代三启用)", example = "0")
    private long totalHealingToday;
}
