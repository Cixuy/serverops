package com.qingruan.websocket.frontend.handler;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.QueryApi;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import com.qingruan.common.config.InfluxDBConfig;
import com.qingruan.entity.Server;
import com.qingruan.mapper.ServerMapper;
import com.qingruan.websocket.common.util.WsUtil;
import com.qingruan.websocket.frontend.model.FeRequest;
import com.qingruan.websocket.frontend.model.FeResponse;
import com.qingruan.websocket.frontend.session.FrontendSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 前端 Web WebSocket 处理器，对应端点 /ws/frontend。
 * <p>
 * 协议（前端 → 后端）：subscribe / unsubscribe / ping<br>
 * 协议（后端 → 前端）：ack / error / pong / server_metrics / server_disk_metrics
 * <p>
 * 鉴权由 {@code AuthHandshakeInterceptor} 在握手阶段完成；
 * 连接建立后，按前端订阅的 serverCode + 指标类型定时从 InfluxDB 拉取最新数据并定向推送。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FrontendWebSocketHandler extends TextWebSocketHandler {

    private static final DateTimeFormatter ISO_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneId.of("UTC"));

    private static final String METRIC_SERVER = "server_metrics";
    private static final String METRIC_DISK = "server_disk_metrics";

    private final FrontendSessionManager frontendSessionManager;
    private final InfluxDBConfig influxConfig;
    private final ObjectProvider<InfluxDBClient> influxDBClientProvider;
    private final ServerMapper serverMapper;

    /**
     * Agent 失联判定阈值（毫秒）。超过该时长 InfluxDB 中无该 server 的新数据点，
     * 即认为 Agent 已停止上报，向前端推送全 0 指标。
     * <p>
     * 默认 20s（Agent 上报周期为 5s，取 4 个周期以规避查询恰好落在上报间隔边界的误判）。
     * 可通过 application.yml 配置 ws.stale-threshold-ms 覆盖。
     */
    @Value("${ws.stale-threshold-ms:20000}")
    private long staleThresholdMs;

    /** sessionId → 订阅的服务器编码集合 */
    private final Map<String, Set<String>> sessionCodes = new ConcurrentHashMap<>();
    /** sessionId → 订阅的指标类型集合 */
    private final Map<String, Set<String>> sessionMetrics = new ConcurrentHashMap<>();
    /** sessionId → 最近一次 ping 时间戳（用于空闲清理） */
    private final Map<String, Long> sessionLastPing = new ConcurrentHashMap<>();

    /**
     * 连续失联计数器：serverCode → 连续被判定为 stale 的次数。
     * <p>
     * 只有连续达到 {@link #STALE_CONFIRM_COUNT} 次才真正推送全 0，
     * 避免 InfluxDB last() 查询时间戳抖动（写入/索引延迟）导致的单次误判产生锯齿。
     * 一旦某轮查询恢复正常（非 stale），计数器立即归零。
     */
    private final Map<String, Integer> staleCounters = new ConcurrentHashMap<>();

    /** 连续多少次判定为 stale 后才确认失联并清零（3 次 × 5s = 15s 确认窗口） */
    private static final int STALE_CONFIRM_COUNT = 3;

    /** 背压：每个 session 的待推送队列；同一 5s 周期内所有服务器/类型的帧都入队，
     *  由发送线程一次性取出并逐条 flush，避免单槽位覆盖导致多服务器/多类型订阅丢帧。 */
    private final Map<String, java.util.List<String>> sessionPending = new ConcurrentHashMap<>();
    /** 发送线程池，把 pending 槽位里的数据异步刷出，避免阻塞定时线程 */
    private final java.util.concurrent.ExecutorService wsSender =
            java.util.concurrent.Executors.newFixedThreadPool(
                    Math.max(2, Runtime.getRuntime().availableProcessors()),
                    r -> { Thread t = new Thread(r, "ws-metrics-sender"); t.setDaemon(true); return t; });

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessionCodes.put(session.getId(), new HashSet<>());
        sessionMetrics.put(session.getId(), new HashSet<>());
        sessionLastPing.put(session.getId(), System.currentTimeMillis());
        frontendSessionManager.register(session.getId(), session,
                (String) session.getAttributes().get("serverCode"));
        log.info("[WS-FE] 前端连接建立: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        FeRequest req = WsUtil.parse(message.getPayload(), FeRequest.class);
        if (req == null || req.getAction() == null) {
            send(session, FeResponse.builder()
                    .type("error").code("INVALID_ACTION").message("action 不能为空").build());
            return;
        }
        switch (req.getAction()) {
            case "subscribe" -> handleSubscribe(session, req);
            case "unsubscribe" -> handleUnsubscribe(session, req);
            case "ping" -> {
                sessionLastPing.put(session.getId(), System.currentTimeMillis());
                send(session, FeResponse.builder()
                        .type("pong").requestId(req.getRequestId())
                        .timestamp(ISO_FMT.format(Instant.now())).build());
            }
            default -> send(session, FeResponse.builder()
                    .type("error").requestId(req.getRequestId())
                    .code("UNKNOWN_ACTION").message("未知 action: " + req.getAction()).build());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionCodes.remove(session.getId());
        sessionMetrics.remove(session.getId());
        sessionLastPing.remove(session.getId());
        frontendSessionManager.remove(session.getId());
        log.info("[WS-FE] 前端连接关闭: {} ({})", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("[WS-FE] 传输异常: {} ({})", session.getId(), exception.getMessage());
    }

    private void handleSubscribe(WebSocketSession session, FeRequest req) {
        Set<String> codes = sessionCodes.computeIfAbsent(session.getId(), k -> new HashSet<>());
        Set<String> types = sessionMetrics.computeIfAbsent(session.getId(), k -> new HashSet<>());
        int valid = 0;
        if (req.getPayload() != null && req.getPayload().getServerCodes() != null) {
            for (String code : req.getPayload().getServerCodes()) {
                if (code == null || code.isBlank()) {
                    continue;
                }
                String sc = code.trim();
                Server server = serverMapper.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Server>()
                                .eq(Server::getServerCode, sc).last("LIMIT 1"));
                if (server == null) {
                    send(session, FeResponse.builder()
                            .type("error").requestId(req.getRequestId())
                            .code("INVALID_SERVER_CODE")
                            .message("Server " + sc + " not found or no permission").build());
                    continue;
                }
                codes.add(sc);
                valid++;
            }
        }
        if (req.getPayload() != null && req.getPayload().getMetrics() != null) {
            for (String m : req.getPayload().getMetrics()) {
                if (METRIC_SERVER.equals(m) || METRIC_DISK.equals(m)) {
                    types.add(m);
                }
            }
        }
        if (types.isEmpty()) {
            types.add(METRIC_SERVER);
            types.add(METRIC_DISK);
        }
        send(session, FeResponse.builder()
                .type("ack").requestId(req.getRequestId()).status("ok")
                .message("Subscribed to " + valid + " servers, " + types.size() + " metric types").build());
    }

    private void handleUnsubscribe(WebSocketSession session, FeRequest req) {
        Set<String> codes = sessionCodes.get(session.getId());
        Set<String> types = sessionMetrics.get(session.getId());
        if (req.getPayload() != null && req.getPayload().getServerCodes() != null && codes != null) {
            req.getPayload().getServerCodes().forEach(c -> codes.remove(c.trim()));
        } else if (codes != null) {
            codes.clear();
        }
        if (types != null) {
            types.clear();
        }
        send(session, FeResponse.builder()
                .type("ack").requestId(req.getRequestId()).status("ok").message("Unsubscribed").build());
    }

    /** 定时推送最新指标，周期由配置 ws.push-interval-ms 决定（默认 1s），按订阅关系定向广播 */
    @Scheduled(fixedDelayString = "${ws.push-interval-ms:1000}")
    public void pushMetrics() {
        if (sessionCodes.isEmpty()) {
            return;
        }
        InfluxDBClient client = influxDBClientProvider.getIfAvailable();
        if (client == null) {
            return;
        }
        Set<String> all = new HashSet<>();
        sessionCodes.values().forEach(all::addAll);
        if (all.isEmpty()) {
            return;
        }
        Set<String> types = new HashSet<>();
        sessionMetrics.values().forEach(types::addAll);
        boolean needServer = types.contains(METRIC_SERVER);
        boolean needDisk = types.contains(METRIC_DISK);
        if (!needServer && !needDisk) {
            return;
        }

        QueryApi queryApi = client.getQueryApi();
        String bucket = influxConfig.getBucket();
        long now = System.currentTimeMillis();

        // —— 批量查询：每个 measurement 仅 1 次请求，替代原循环单条查询 ——
        Map<String, Map<String, Object>> serverMap = needServer
                ? queryLatestBatch(queryApi, bucket, all, "server_metrics")
                : java.util.Collections.emptyMap();
        Map<String, List<Map<String, Object>>> diskMap = needDisk
                ? queryDiskBatch(queryApi, bucket, all)
                : java.util.Collections.emptyMap();

        // —— 按 serverCode 分发（stale 判定逻辑保持原样） ——
        for (String serverCode : all) {
            Map<String, Object> metric = serverMap.get(serverCode);
            List<Map<String, Object>> disks = diskMap.get(serverCode);
            Long latestTimeMs = metric != null ? (Long) metric.get("@time")
                    : (disks != null && !disks.isEmpty() ? (Long) disks.get(0).get("@time") : null);

            // 区分两种"无数据"：
            // 1) latestTimeMs == null：该服务器从未上报过数据（刚订阅/刚添加/Agent 尚未启动），
            //    不误判为离线，也不推全 0，本轮静默跳过，等待其首次上报。
            // 2) 有数据但距今超过阈值：疑似失联，走 stale 计数确认逻辑。
            if (latestTimeMs == null) {
                staleCounters.remove(serverCode);
                continue;
            }
            boolean stale = (now - latestTimeMs) > staleThresholdMs;
            if (stale) {
                int count = staleCounters.merge(serverCode, 1, Integer::sum);
                if (count < STALE_CONFIRM_COUNT) {
                    // 连续失联次数未达阈值，本轮跳过不清零（静默等待确认）
                    log.debug("[WS] serverCode={} stale 次数 {}/{}，暂不清零", serverCode, count, STALE_CONFIRM_COUNT);
                    continue;
                }
                // 连续达到确认次数，真正清零
                log.info("[WS] serverCode={} 连续 {} 次 stale，确认为离线，推送全 0", serverCode, count);
            } else {
                // 本轮正常，重置计数器
                staleCounters.remove(serverCode);
            }
            if (stale) {
                broadcastZero(serverCode, needServer, needDisk, metric, disks, now);
                continue;
            }

            if (needServer && metric != null) {
                broadcast(serverCode, METRIC_SERVER, metric, null);
            }
            if (needDisk && disks != null && !disks.isEmpty()) {
                broadcast(serverCode, METRIC_DISK, null, disks);
            }
        }
    }

    /** server_metrics 批量查询：一次请求返回所有 serverCode 的最新一条（每个字段独立取最新，避免 last()+pivot 丢字段） */
    private Map<String, Map<String, Object>> queryLatestBatch(QueryApi queryApi, String bucket,
                                                              Set<String> codes, String measurement) {
        // 关键修复：原写法 group(server_code) + last() + pivot 会在「整行 _time 最大」时丢失其余字段，
        // 导致 server_metrics 只剩 network_out_kbps 一个字段。
        // 现改为：filter -> map(float) -> group(columns:["server_code"])（仅分组，不 last），
        // 由 Java 在应用层对每个 server 的每个 _field 取 _time 最大的值，保证所有字段都取到。
        String flux = String.format(
                "from(bucket:\"%s\")"
                        + "|> range(start: -5m)"
                        + "|> filter(fn: (r) => r._measurement == \"%s\" and contains(value: r.server_code, set: %s))"
                        + "|> map(fn: (r) => ({ r with _value: float(v: r._value) }))"
                        + "|> group(columns: [\"server_code\"])",
                bucket, measurement, toFluxSet(codes));
        Map<String, Map<String, Object>> result = new java.util.HashMap<>();
        try {
            for (FluxTable table : queryApi.query(flux)) {
                for (FluxRecord rec : table.getRecords()) {
                    Map<String, Object> values = rec.getValues();
                    String sc = (String) values.get("server_code");
                    if (sc == null) continue;
                    String field = (String) values.get("_field");
                    Object t = values.get("_time");
                    long epoch = toEpochMilli(t);
                    Map<String, Object> m = result.computeIfAbsent(sc, k -> new HashMap<>());
                    // 该字段已存在且时间戳更新则跳过（保留每字段最新值）
                    Object prev = m.get("@time:" + field);
                    if (prev == null || epoch >= (Long) prev) {
                        m.put(field, values.get("_value"));
                        m.put("@time:" + field, epoch);
                        // 记录该 server 整体最新时间（用于 stale 判定，取各字段最新 _time 的最大值）
                        Long overall = (Long) m.get("@time");
                        if (overall == null || epoch > overall) m.put("@time", epoch);
                    }
                }
            }
            // 清理临时标记并补充 hostname
            for (Map.Entry<String, Map<String, Object>> e : result.entrySet()) {
                String sc = e.getKey();
                Map<String, Object> m = e.getValue();
                m.keySet().removeIf(k -> k.startsWith("@time:"));
                m.put("hostname", sc);
            }
        } catch (Exception e) {
            log.warn("[WS] 批量查询 {} 失败: {}", measurement, e.getMessage());
        }
        return result;
    }

    /** server_disk_metrics 批量查询：按 server_code + mount_point 分组，每个字段独立取最新 */
    private Map<String, List<Map<String, Object>>> queryDiskBatch(QueryApi queryApi, String bucket,
                                                                  Set<String> codes) {
        // 与 queryLatestBatch 同理：原 group(server_code, mount_point) + last() + pivot 会因
        // 整行 _time 最大而丢失部分字段（如 disk_read_kbps / disk_iops_xxx 等）。
        // 现改为仅 group，由 Java 应用层对每个 (server_code, mount_point) 的每个 _field 取最新值。
        String flux = String.format(
                "from(bucket:\"%s\")"
                        + "|> range(start: -5m)"
                        + "|> filter(fn: (r) => r._measurement == \"server_disk_metrics\" and contains(value: r.server_code, set: %s))"
                        + "|> map(fn: (r) => ({ r with _value: float(v: r._value) }))"
                        + "|> group(columns: [\"server_code\", \"mount_point\"])",
                bucket, toFluxSet(codes));
        // 临时结构：server_code -> mount_point -> (field -> 聚合 map)
        Map<String, Map<String, Map<String, Object>>> tmp = new java.util.HashMap<>();
        try {
            for (FluxTable table : queryApi.query(flux)) {
                for (FluxRecord rec : table.getRecords()) {
                    Map<String, Object> values = rec.getValues();
                    String sc = (String) values.get("server_code");
                    String mp = (String) values.get("mount_point");
                    if (sc == null || mp == null) continue;
                    String field = (String) values.get("_field");
                    long epoch = toEpochMilli(values.get("_time"));
                    Map<String, Object> m = tmp.computeIfAbsent(sc, k -> new java.util.HashMap<>())
                            .computeIfAbsent(mp, k -> {
                                Map<String, Object> init = new java.util.HashMap<>();
                                // 保留 tag 列（mount_point / fs_type 等），它们是挂载点标识而非 field
                                init.put("mount_point", mp);
                                init.put("server_code", sc);
                                for (Map.Entry<String, Object> kv : values.entrySet()) {
                                    String key = kv.getKey();
                                    if (!key.startsWith("_") && !key.equals("server_code")
                                            && !key.equals("mount_point") && !key.equals("_value")
                                            && !key.equals("result") && !key.equals("table")) {
                                        init.put(key, kv.getValue());
                                    }
                                }
                                return init;
                            });
                    Object prev = m.get("@time:" + field);
                    if (prev == null || epoch >= (Long) prev) {
                        m.put(field, values.get("_value"));
                        m.put("@time:" + field, epoch);
                        Long overall = (Long) m.get("@time");
                        if (overall == null || epoch > overall) m.put("@time", epoch);
                    }
                }
            }
            Map<String, List<Map<String, Object>>> result = new java.util.HashMap<>();
            for (Map.Entry<String, Map<String, Map<String, Object>>> e : tmp.entrySet()) {
                List<Map<String, Object>> list = new java.util.ArrayList<>();
                for (Map<String, Object> m : e.getValue().values()) {
                    m.keySet().removeIf(k -> k.startsWith("@time:"));
                    list.add(m);
                }
                result.put(e.getKey(), list);
            }
            return result;
        } catch (Exception e) {
            log.warn("[WS] 批量查询 server_disk_metrics 失败: {}", e.getMessage());
            return java.util.Collections.emptyMap();
        }
    }

    /** 把 Set<String> 转成 Flux 的数组字面量，如 ["a","b"]（已做基础转义） */
    private String toFluxSet(Set<String> codes) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String c : codes) {
            if (c == null || c.isBlank()) continue;
            if (!first) sb.append(", ");
            sb.append("\"").append(c.replace("\"", "\\\"")).append("\"");
            first = false;
        }
        return sb.append("]").toString();
    }

    /**
     * Agent 失联时的清零推送：server 基础指标全部置 0；disk 复用最近一次挂载点信息
     * （保留 mount_point / fs_type 便于前端识别），其余指标置 0。
     * 若某类型从未上报过数据（disks 为 null），则不推送该类型。
     */
    private void broadcastZero(String serverCode, boolean needServer, boolean needDisk,
                               Map<String, Object> metric, List<Map<String, Object>> disks, long now) {
        if (needServer) {
            Map<String, Object> zero = new HashMap<>();
            zero.put("cpu_usage", 0);
            zero.put("cpu_count", metric != null ? metric.getOrDefault("cpu_count", 0) : 0);
            zero.put("memory_usage", 0);
            zero.put("memory_used_mb", 0);
            zero.put("memory_total_mb", 0);
            zero.put("network_in_kbps", 0);
            zero.put("network_out_kbps", 0);
            zero.put("hostname", metric != null ? metric.getOrDefault("hostname", serverCode) : serverCode);
            zero.put("@time", now);
            broadcast(serverCode, METRIC_SERVER, zero, null);
        }
        if (needDisk && disks != null && !disks.isEmpty()) {
            for (Map<String, Object> d : disks) {
                d.put("disk_iops_read", 0);
                d.put("disk_iops_write", 0);
                d.put("disk_read_kbps", 0);
                d.put("disk_write_kbps", 0);
                d.put("disk_usage", 0);
                d.put("disk_used_gb", 0);
                d.put("disk_total_gb", 0);
                d.put("@time", now);
            }
            broadcast(serverCode, METRIC_DISK, null, disks);
        }
    }

    private void broadcast(String serverCode, String type, Map<String, Object> serverData,
                           List<Map<String, Object>> diskData) {
        Long timeMs;
        if (serverData != null) {
            timeMs = (Long) serverData.remove("@time");
            // 剔除 InfluxDB 系统列，向前端下发干净业务字段
            serverData.remove("result");
            serverData.remove("table");
            serverData.remove("_time");
        } else {
            Map<String, Object> first = diskData.get(0);
            timeMs = (Long) first.remove("@time");
            for (Map<String, Object> d : diskData) {
                d.remove("@time");
                d.remove("result");
                d.remove("table");
                d.remove("_time");
            }
        }
        String ts = ISO_FMT.format(Instant.ofEpochMilli(timeMs == null ? System.currentTimeMillis() : timeMs));
        for (Map.Entry<String, Set<String>> e : sessionCodes.entrySet()) {
            if (!e.getValue().contains(serverCode)) {
                continue;
            }
            Set<String> types = sessionMetrics.get(e.getKey());
            if (type.equals(METRIC_SERVER) && (types == null || !types.contains(METRIC_SERVER))) {
                continue;
            }
            if (type.equals(METRIC_DISK) && (types == null || !types.contains(METRIC_DISK))) {
                continue;
            }
            WebSocketSession s = frontendSessionManager.get(e.getKey());
            if (s == null || !s.isOpen()) {
                continue;
            }

            // 背压策略：本周期内所有帧入队，发送线程取出并逐条刷出（避免单槽位覆盖丢帧）
            String payload = WsUtil.toJson(FeResponse.builder().type(type).serverCode(serverCode)
                    .timestamp(ts).data(serverData != null ? serverData : diskData).build());
            sessionPending.computeIfAbsent(e.getKey(), k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(payload);
            wsSender.execute(() -> {
                java.util.List<String> frames = sessionPending.remove(e.getKey()); // 取出并清空队列
                if (frames == null || frames.isEmpty()) {
                    return;
                }
                for (String p : frames) {
                    try {
                        s.sendMessage(new TextMessage(p)); // 异步发送，绕开同步阻塞
                    } catch (Exception ex) {
                        // 发送失败（网络抖动/连接关闭）：丢弃本帧，下一轮 5s 后自然补最新
                        log.debug("[WS] 推送失败，丢弃本帧: session={}, {}", e.getKey(), ex.getMessage());
                    }
                }
            });
        }
    }

    /** InfluxDB 返回的 _time 为 Instant，转为 epoch 毫秒；无法解析时返回 0，
     *  使其必然落入 stale 分支（而非误判为"刚刚在线"导致永不失联）。 */
    private long toEpochMilli(Object timeObj) {
        if (timeObj instanceof Instant instant) {
            return instant.toEpochMilli();
        }
        log.warn("[WS] 无法解析数据点时间(非 Instant): {}，按失联处理", timeObj);
        return 0L;
    }

    private void send(WebSocketSession session, FeResponse resp) {
        frontendSessionManager.send(session, WsUtil.toJson(resp));
    }
}
