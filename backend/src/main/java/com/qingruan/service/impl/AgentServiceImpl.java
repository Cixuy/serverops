package com.qingruan.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.influxdb.client.InfluxDBClient;
import jakarta.annotation.PostConstruct;
import com.influxdb.client.QueryApi;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import com.qingruan.common.config.InfluxDBConfig;
import com.qingruan.dto.request.HeartbeatRequest;
import com.qingruan.dto.request.MetricsReportRequest;
import com.qingruan.dto.request.ServerRegisterRequest;
import com.qingruan.dto.response.MetricsQueryResponse;
import com.qingruan.dto.response.ServerRegisterResponse;
import com.qingruan.entity.Server;
import com.qingruan.mapper.ServerMapper;
import com.qingruan.service.AgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Agent 接入服务实现
 * <p>
 * 注册信息 / 心跳状态落 MySQL（server 表）；监控指标写入 InfluxDB 时序库。
 * 心跳超时（默认 60s 无上报）由定时任务将服务器置为离线。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentServiceImpl implements AgentService {

    /** 心跳超时阈值(ms)：超过该时间未上报心跳即判定离线 */
    private static final long HEARTBEAT_TIMEOUT_MS = 60_000L;

    /**
     * 每个 server 专属的心跳倒计时任务。
     * 注册时创建，每次收到心跳则取消旧任务并重新调度（续期），
     * 倒计时到点仍未收到心跳则置离线并结束任务。无需全局扫库。
     */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> heartbeatTasks = new ConcurrentHashMap<>();

    private final InfluxDBConfig influxConfig;
    private final ObjectProvider<InfluxDBClient> influxDBClientProvider;
    private final ServerMapper serverMapper;
    private final TaskScheduler taskScheduler;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ServerRegisterResponse register(ServerRegisterRequest request) {
        LocalDateTime now = LocalDateTime.now();

        // 幂等处理：同一 hostname + ip 视为同一台服务器，已存在则复用 server_code / serverId
        Server existing = serverMapper.selectOne(new LambdaQueryWrapper<Server>()
                .eq(Server::getHostname, request.getHostname())
                .eq(Server::getIpAddress, request.getIpAddress())
                .last("LIMIT 1"));

        Server server;
        if (existing == null) {
            server = new Server();
            server.setServerCode(generateServerCode());
            server.setStatus(1);
            server.setLastHeartbeat(now);
            server.setCreatedAt(now);
            server.setUpdatedAt(now);
            fillRegisterFields(server, request);
            serverMapper.insert(server);   // 自增 id 在 insert 后回填到 server.getId()
        } else {
            server = existing;
            server.setStatus(1);
            server.setLastHeartbeat(now);
            server.setUpdatedAt(now);
            fillRegisterFields(server, request);
            serverMapper.updateById(server);
        }

        ServerRegisterResponse response = new ServerRegisterResponse();
        response.setServerCode(server.getServerCode());
        response.setServerId(server.getId());   // 使用数据库自增主键作为 serverId

        // 注册成功后，为该 server 启动专属的 60s 心跳倒计时任务
        scheduleHeartbeatCheck(server.getServerCode());
        return response;
    }

    /** 将注册请求字段填充到 Server 实体 */
    private void fillRegisterFields(Server server, ServerRegisterRequest request) {
        server.setHostname(request.getHostname());
        server.setIpAddress(request.getIpAddress());
        server.setOsType(request.getOsType());
        server.setOsVersion(request.getOsVersion());
        server.setCpuInfo(request.getCpuInfo());
        server.setMemoryInfo(request.getMemoryInfo());
        server.setDiskInfo(request.getDiskInfo());
        server.setAgentVersion(request.getAgentVersion());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void heartbeat(HeartbeatRequest request) {
        // 客户端时间戳(ms) → LocalDateTime；缺失则用服务端当前时间
        LocalDateTime heartbeatTime = request.getTimestamp() == null
                ? LocalDateTime.now()
                : LocalDateTime.ofInstant(Instant.ofEpochMilli(request.getTimestamp()), ZoneId.systemDefault());

        Server server = serverMapper.selectOne(new LambdaQueryWrapper<Server>()
                .eq(Server::getServerCode, request.getServerCode())
                .last("LIMIT 1"));

        if (server == null) {
            log.warn("[Agent] 心跳上报的 serverCode 不存在: {}", request.getServerCode());
            throw new IllegalArgumentException("serverCode 不存在: " + request.getServerCode());
        }

        server.setStatus(1);                       // 收到心跳即视为在线
        server.setLastHeartbeat(heartbeatTime);
        server.setUpdatedAt(LocalDateTime.now());
        serverMapper.updateById(server);

        // 收到心跳即续期：取消旧的倒计时任务，重新调度 60s
        scheduleHeartbeatCheck(request.getServerCode());
    }

    /**
     * 为该 server 创建/重置一个延迟 HEARTBEAT_TIMEOUT_MS 执行的专属倒计时任务。
     * 任务到点时检查最后一次心跳时间，若已超时则置离线；任务自然结束（无需全局扫库）。
     * 心跳上报会调用本方法取消并重建任务，实现“续期”。
     */
    private void scheduleHeartbeatCheck(String serverCode) {
        // 先取消已有任务（若存在且未结束），避免重复堆积
        ScheduledFuture<?> prev = heartbeatTasks.get(serverCode);
        if (prev != null && !prev.isDone()) {
            prev.cancel(false);
        }

        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> onHeartbeatTimeout(serverCode),
                Instant.now().plusMillis(HEARTBEAT_TIMEOUT_MS));
        heartbeatTasks.put(serverCode, future);
        log.debug("[Agent] 已为 {} 启动/续期 心跳倒计时任务 ({}ms)", serverCode, HEARTBEAT_TIMEOUT_MS);
    }

    /**
     * 应用启动一次性补偿（非周期性扫描任务）。
     * <p>
     * 心跳计时任务仅存于 JVM 内存，应用重启后 heartbeatTasks 会清空，
     * 导致数据库 server_info 中 status=1 但早已离线的历史记录无法被纠正。
     * 启动时：对 status=1 的记录，心跳已超阈值的直接置离线（修正 server_info.status），
     * 仍在线的重建内存计时任务，使"单独计时"模型在重启后依然成立。
     */
    @PostConstruct
    public void initHeartbeatTasksOnStartup() {
        List<Server> onlineServers = serverMapper.selectList(
                new LambdaQueryWrapper<Server>().eq(Server::getStatus, 1));
        if (onlineServers == null || onlineServers.isEmpty()) {
            return;
        }
        LocalDateTime deadline = LocalDateTime.now().minusSeconds(HEARTBEAT_TIMEOUT_MS / 1000);
        for (Server server : onlineServers) {
            if (server.getLastHeartbeat() == null || server.getLastHeartbeat().isBefore(deadline)) {
                server.setStatus(0);
                server.setUpdatedAt(LocalDateTime.now());
                serverMapper.updateById(server);
                heartbeatTasks.remove(server.getServerCode());
                log.info("[Agent] 启动补偿：服务器 {} 心跳早已超时，置为离线", server.getServerCode());
            } else {
                scheduleHeartbeatCheck(server.getServerCode());
            }
        }
    }

    /**
     * 倒计时到点回调：若该 server 最后一次心跳已超过阈值，则置离线并清理任务。
     */
    @Transactional(rollbackFor = Exception.class)
    public void onHeartbeatTimeout(String serverCode) {
        try {
            Server server = serverMapper.selectOne(new LambdaQueryWrapper<Server>()
                    .eq(Server::getServerCode, serverCode)
                    .last("LIMIT 1"));
            if (server == null) {
                heartbeatTasks.remove(serverCode);
                return;
            }

            LocalDateTime deadline = LocalDateTime.now().minusSeconds(HEARTBEAT_TIMEOUT_MS / 1000);
            boolean timedOut = server.getLastHeartbeat() == null
                    || server.getLastHeartbeat().isBefore(deadline);

            if (timedOut && server.getStatus() != 0) {
                server.setStatus(0);
                server.setUpdatedAt(LocalDateTime.now());
                serverMapper.updateById(server);
                log.info("[Agent] 心跳超时：服务器 {} 已置为离线", serverCode);
            }
        } finally {
            // 任务执行完毕即结束；若未超时（理论上不会发生，因心跳会续期），移除引用等待下次心跳重建
            heartbeatTasks.remove(serverCode);
        }
    }

    // InfluxDB 保留策略时长（需与 InfluxDB 服务端配置一致，默认 336h = 14d）
    private static final long RETENTION_POLICY_HOURS = 336L;

    @Override
    public void saveMetrics(MetricsReportRequest request) {
        // 时间戳兜底：请求时间戳缺失，或早于保留策略下限时，自动改用当前时间，
        // 避免写入超出保留期的数据被 InfluxDB 丢弃并返回 422。
        long now = System.currentTimeMillis();
        long lowerBound = now - RETENTION_POLICY_HOURS * 3_600_000L;
        long effectiveTs = request.getTimestamp() != null && request.getTimestamp() >= lowerBound
                ? request.getTimestamp()
                : now;

        LocalDateTime collectTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(effectiveTs), ZoneId.systemDefault());

        // 监控指标仅写入 InfluxDB 时序库
        try {
            writeToInfluxDB(request, collectTime);
        } catch (Exception e) {
            log.warn("[Agent] InfluxDB 写入失败: {}", e.getMessage());
        }
    }

    /**
     * 将指标写入 InfluxDB 时序库
     */
    private void writeToInfluxDB(MetricsReportRequest request, LocalDateTime collectTime) {
        InfluxDBClient client = influxDBClientProvider.getIfAvailable();
        if (client == null) {
            return;
        }

        Instant ts = collectTime.atZone(ZoneId.systemDefault()).toInstant();
        String bucket = influxConfig.getBucket();
        String org = influxConfig.getOrg();

        WriteApiBlocking writeApi = client.getWriteApiBlocking();
        // 服务器基础指标
        MetricsReportRequest.Metrics m = request.getMetrics();
        Point metricPoint = Point.measurement("server_metrics")
                .addTag("server_code", request.getServerCode())
                .addTag("hostname", escapeTagValue(request.getHostname()))
                .addField("cpu_usage", m.getCpuUsage())
                .addField("cpu_count", m.getCpuCount() == null ? 0 : m.getCpuCount())
                .addField("memory_usage", m.getMemoryUsage())
                .addField("memory_used_mb", m.getMemoryUsedMb() == null ? 0 : m.getMemoryUsedMb())
                .addField("memory_total_mb", m.getMemoryTotalMb() == null ? 0 : m.getMemoryTotalMb())
                .addField("network_in_kbps", m.getNetworkInKbps())
                .addField("network_out_kbps", m.getNetworkOutKbps())
                .time(ts, WritePrecision.MS);
        writeApi.writePoint(bucket, org, metricPoint);

        // 磁盘指标
        if (request.getDisks() != null) {
            for (MetricsReportRequest.DiskMetric d : request.getDisks()) {
                Point diskPoint = Point.measurement("server_disk_metrics")
                        .addTag("server_code", request.getServerCode())
                        .addTag("mount_point", escapeTagValue(d.getMountPoint()))
                        .addField("disk_read_kbps", d.getDiskReadKbps() == null ? 0 : d.getDiskReadKbps())
                        .addField("disk_write_kbps", d.getDiskWriteKbps() == null ? 0 : d.getDiskWriteKbps())
                        .addField("disk_iops_read", d.getDiskIopsRead() == null ? 0 : d.getDiskIopsRead())
                        .addField("disk_iops_write", d.getDiskIopsWrite() == null ? 0 : d.getDiskIopsWrite())
                        .addField("disk_usage", d.getDiskUsage())
                        .addField("disk_used_gb", d.getDiskUsedGb() == null ? 0 : d.getDiskUsedGb())
                        .addField("disk_total_gb", d.getDiskTotalGb() == null ? 0 : d.getDiskTotalGb())
                        .time(ts, WritePrecision.MS);
                writeApi.writePoint(bucket, org, diskPoint);
            }
        }
    }

    /**
     * 非空字符串字段才写入，避免 InfluxDB Point 对 null 值抛异常
     */
    private void addStringField(Point point, String field, String value) {
        if (value != null) {
            point.addField(field, value);
        }
    }

    /**
     * 转义 Flux 字符串字面量中的特殊字符
     */
    private String fluxEscape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 清洗 InfluxDB line protocol tag 值。
     * <p>
     * influxdb-client-java 自动转义空格、逗号、等号，但不处理反斜杠。
     * Windows 挂载点如 "C:\" 结尾的反斜杠会让 InfluxDB 把后续逗号一并转义，
     * 且 tag 值以 \ 结尾本身非法（invalid tag format）。
     * 故将反斜杠统一归一化为正斜杠：line protocol 合法、不丢失路径信息、
     * 跨平台通用（前端如需 "C:\" 样式可自行替换）。
     */
    private String escapeTagValue(String value) {
        return value == null ? null : value.replace("\\", "/");
    }

    /**
     * 生成服务器唯一编码，格式: SRV-XXXXXXXX
     */
    private String generateServerCode() {
        return "SRV-" + RandomUtil.randomStringUpper(8);
    }

    @Override
    public MetricsQueryResponse queryMetrics(String serverCode, String range) {
        InfluxDBClient client = influxDBClientProvider.getIfAvailable();
        MetricsQueryResponse response = new MetricsQueryResponse();
        response.setServerCode(serverCode);
        response.setTimeRange(range);
        if (client == null) {
            return response;
        }

        String start = mapRangeToStart(range);
        QueryApi queryApi = client.getQueryApi();

        // 1) 基础指标序列（server_metrics），按时间升序
        String metricsFlux = "from(bucket: \"" + influxConfig.getBucket() + "\") "
                + "|> range(start: " + start + ") "
                + "|> filter(fn: (r) => r._measurement == \"server_metrics\" "
                + "and r.server_code == \"" + fluxEscape(serverCode) + "\") "
                + "|> sort(columns: [\"_time\"])";

        // 每个时间戳点: field -> value
        Map<Long, Map<String, Object>> metricsByTime = new HashMap<>();
        for (FluxTable table : safeQuery(queryApi, metricsFlux)) {
            for (FluxRecord record : table.getRecords()) {
                long t = record.getTime().toEpochMilli();
                metricsByTime.computeIfAbsent(t, k -> new HashMap<>()).put(record.getField(), record.getValue());
            }
        }

        List<Long> timestamps = new ArrayList<>(metricsByTime.keySet());
        Collections.sort(timestamps);
        response.setTimestamps(timestamps);
        response.setCpuUsage(extractSeries(metricsByTime, timestamps, "cpu_usage"));
        response.setMemoryUsage(extractSeries(metricsByTime, timestamps, "memory_usage"));
        response.setNetworkInKbps(extractSeries(metricsByTime, timestamps, "network_in_kbps"));
        response.setNetworkOutKbps(extractSeries(metricsByTime, timestamps, "network_out_kbps"));

        // 2) 磁盘指标（server_disk_metrics），取各 mountPoint 最新一条
        String diskFlux = "from(bucket: \"" + influxConfig.getBucket() + "\") "
                + "|> range(start: " + start + ") "
                + "|> filter(fn: (r) => r._measurement == \"server_disk_metrics\" "
                + "and r.server_code == \"" + fluxEscape(serverCode) + "\") "
                + "|> sort(columns: [\"_time\"]) "
                + "|> last()";

        Map<String, Map<String, Object>> diskByMount = new LinkedHashMap<>();
        for (FluxTable table : safeQuery(queryApi, diskFlux)) {
            for (FluxRecord record : table.getRecords()) {
                String mount = (String) record.getValueByKey("mount_point");
                if (mount == null) {
                    continue;
                }
                diskByMount.computeIfAbsent(mount, k -> new LinkedHashMap<>())
                        .put(record.getField(), record.getValue());
            }
        }

        List<Map<String, Object>> disks = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : diskByMount.entrySet()) {
            Map<String, Object> disk = new LinkedHashMap<>(entry.getValue());
            disk.put("mountPoint", entry.getKey());
            disks.add(disk);
        }
        response.setDisks(disks);

        return response;
    }

    /**
     * 将 range 枚举映射为 Flux 的 range start 表达式
     */
    private String mapRangeToStart(String range) {
        if (range == null) {
            return "-1h";
        }
        return switch (range) {
            case "30m" -> "-30m";
            case "1h" -> "-1h";
            case "6h" -> "-6h";
            case "1d" -> "-1d";
            default -> "-1h";
        };
    }

    /**
     * 按时间戳顺序提取某个 field 的序列值，缺失点补 null
     */
    private List<Double> extractSeries(Map<Long, Map<String, Object>> byTime,
                                       List<Long> timestamps, String field) {
        List<Double> series = new ArrayList<>(timestamps.size());
        for (Long t : timestamps) {
            Object v = byTime.get(t).get(field);
            if (v == null) {
                series.add(null);
            } else if (v instanceof Number n) {
                series.add(n.doubleValue());
            } else {
                series.add(null);
            }
        }
        return series;
    }

    /**
     * 安全执行 Flux 查询，异常时返回空列表不中断主流程
     */
    private List<FluxTable> safeQuery(QueryApi queryApi, String flux) {
        try {
            return queryApi.query(flux);
        } catch (Exception e) {
            log.warn("[Agent] InfluxDB 查询失败: {}", e.getMessage());
            return List.of();
        }
    }
}
