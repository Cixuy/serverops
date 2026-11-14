package com.qingruan.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * Agent 上报监控指标请求
 */
@Data
public class MetricsReportRequest {

    /** 服务器唯一编码 */
    @NotBlank(message = "serverCode不能为空")
    private String serverCode;

    /** 主机名 */
    private String hostname;

    /** 采集时间戳(ms) */
    private Long timestamp;

    /** 服务器基础指标（仅写入 InfluxDB 时序库） */
    @NotNull(message = "metrics不能为空")
    @Valid
    private Metrics metrics;

    /** 磁盘指标列表（仅写入 InfluxDB 时序库，每块磁盘为一个元素） */
    @Valid
    private List<DiskMetric> disks;

    @Data
    public static class Metrics {

        /** CPU使用率(%) */
        @NotNull(message = "cpuUsage不能为空")
        private Double cpuUsage;

        /** CPU核心数 */
        private Integer cpuCount;

        /** 内存使用率(%) */
        @NotNull(message = "memoryUsage不能为空")
        private Double memoryUsage;

        /** 已用内存(MB) */
        private Double memoryUsedMb;

        /** 总内存(MB) */
        private Double memoryTotalMb;

        /** 网络下行(KB/s) */
        @NotNull(message = "networkInKbps不能为空")
        private Double networkInKbps;

        /** 网络上行(KB/s) */
        @NotNull(message = "networkOutKbps不能为空")
        private Double networkOutKbps;
    }

    @Data
    public static class DiskMetric {

        /** 挂载点路径 */
        @NotBlank(message = "挂载点不能为空")
        private String mountPoint;

        /** 文件系统类型 */
        private String fsType;

        /** 磁盘读取吞吐(KB/s) */
        private Double diskReadKbps;

        /** 磁盘写入吞吐(KB/s) */
        private Double diskWriteKbps;

        /** 每秒读操作数(IOPS) */
        private Integer diskIopsRead;

        /** 每秒写操作数(IOPS) */
        private Integer diskIopsWrite;

        /** 磁盘空间使用率(%) */
        @NotNull(message = "diskUsage不能为空")
        private Double diskUsage;

        /** 已用空间(GB) */
        private Double diskUsedGb;

        /** 总空间(GB) */
        private Double diskTotalGb;
    }
}
