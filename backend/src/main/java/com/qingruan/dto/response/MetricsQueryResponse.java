package com.qingruan.dto.response;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 时序指标查询结果
 */
@Data
public class MetricsQueryResponse {

    /** 服务器唯一编码 */
    private String serverCode;

    /** 查询时间范围 */
    private String timeRange;

    /** 时间戳数组(ms)，所有指标数组与此等长对齐 */
    private List<Long> timestamps;

    /** CPU使用率(%) */
    private List<Double> cpuUsage;

    /** 内存使用率(%) */
    private List<Double> memoryUsage;

    /** 网络下行(KB/s) */
    private List<Double> networkInKbps;

    /** 网络上行(KB/s) */
    private List<Double> networkOutKbps;

    /** 磁盘指标列表（对应 server_disk_metrics，按 mountPoint 聚合最新值） */
    private List<Map<String, Object>> disks;
}
