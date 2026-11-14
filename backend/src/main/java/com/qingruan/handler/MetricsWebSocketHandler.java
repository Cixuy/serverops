package com.qingruan.handler;

import cn.hutool.json.JSONUtil;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.QueryApi;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import com.qingruan.common.config.InfluxDBConfig;
import com.qingruan.dto.ws.ClientMessage;
import com.qingruan.dto.ws.ServerMessage;
import com.qingruan.entity.MonitorServiceConfig;
import com.qingruan.entity.Server;
import com.qingruan.mapper.ServerMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实时指标 WebSocket 处理器
 * <p>
 * 协议（前端 → 服务端）：subscribe / unsubscribe / ping<br>
 * 协议（服务端 → 前端）：ack / error / server_metrics / server_disk_metrics
 * <p>
 * 维护每个会话的订阅关系，定时从 InfluxDB 拉取各订阅服务器的最新指标并推送。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricsWebSocketHandler extends TextWebSocketHandler {

    private static final DateTimeFormatter ISO_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                    .withZone(ZoneId.of("UTC"));

    private static final String METRIC_SERVER = "server_metrics";
    private static final String METRIC_DISK = "server_disk_metrics";

    /** 前端心跳空闲超时阈值(ms)：超过该时间未收到 ping 则主动断开 */
    private static final long WS_PING_TIMEOUT_MS = 60_000L;
    /** 空闲扫描周期(ms) */
    private static final long WS_IDLE_SCAN_PERIOD_MS = 30_000L;

    private final com.qingruan.common.security.SocketTokenValidator tokenValidator;
    private final InfluxDBConfig influxConfig;
    private final ObjectProvider<InfluxDBClient> influxDBClientProvider;
    private final ServerMapper serverMapper;

    /** sessionId → 该会话订阅的服务器编码集合 */
    private final Map<String, Set<String>> sessionSubscriptions = new ConcurrentHashMap<>();
    /** sessionId → 该会话订阅的指标类型集合（server_metrics / server_disk_metrics） */
    private final Map<String, Set<String>> sessionMetricTypes = new ConcurrentHashMap<>();
    /** sessionId → 该会话最后一次收到 ping 的时间戳(ms) */
    private final Map<String, Long> sessionLastPing = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // 鉴权：校验 query 参数 SocketToken（本质是用户登录后的认证 token）
        String token = extractSocketToken(session);
        if (!tokenValidator.validate(token)) {
            log.warn("[WS] 连接被拒绝：SocketToken 无效 (session={})", session.getId());
            send(session, ServerMessage.builder()
                    .type("error")
                    .code("UNAUTHORIZED")
                    .message("Invalid SocketToken")
                    .build());
            try {
                session.close(CloseStatus.NOT_ACCEPTABLE);
            } catch (IOException ignored) {
            }
            return;
        }
        sessionSubscriptions.put(session.getId(), new HashSet<>());
        sessionMetricTypes.put(session.getId(), new HashSet<>());
        sessionLastPing.put(session.getId(), System.currentTimeMillis());
        sessionCache.put(session.getId(), session);
        log.info("[WS] 连接建立: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ClientMessage req;
        try {
            req = JSONUtil.toBean(message.getPayload(), ClientMessage.class);
        } catch (Exception e) {
            send(session, ServerMessage.builder()
                    .type("error")
                    .code("INVALID_JSON")
                    .message("消息体不是合法 JSON")
                    .build());
            return;
        }
        if (req == null || req.getAction() == null) {
            send(session, ServerMessage.builder()
                    .type("error")
                    .code("INVALID_ACTION")
                    .message("action 不能为空")
                    .build());
            return;
        }

        switch (req.getAction()) {
            case "subscribe" -> handleSubscribe(session, req);
            case "unsubscribe" -> handleUnsubscribe(session, req);
            case "ping" -> {
                sessionLastPing.put(session.getId(), System.currentTimeMillis());
                send(session, ServerMessage.builder()
                        .type("pong")
                        .timestamp(ISO_FMT.format(Instant.now()))
                        .build());
            }
            default -> send(session, ServerMessage.builder()
                    .type("error")
                    .requestId(req.getRequestId())
                    .code("UNKNOWN_ACTION")
                    .message("未知 action: " + req.getAction())
                    .build());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionSubscriptions.remove(session.getId());
        sessionMetricTypes.remove(session.getId());
        sessionLastPing.remove(session.getId());
        sessionCache.remove(session.getId());
        log.info("[WS] 连接关闭: {} ({})", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("[WS] 传输异常: {} ({})", session.getId(), exception.getMessage());
    }

    // ===================== 订阅 / 取消订阅 =====================

    private void handleSubscribe(WebSocketSession session, ClientMessage req) {
        List<String> requestedCodes = req.getPayload() == null ? null : req.getPayload().getServerCodes();
        List<String> requestedMetrics = req.getPayload() == null ? null : req.getPayload().getMetrics();

        Set<String> codes = sessionSubscriptions.computeIfAbsent(session.getId(), k -> new HashSet<>());
        Set<String> types = sessionMetricTypes.computeIfAbsent(session.getId(), k -> new HashSet<>());

        int validServers = 0;
        if (requestedCodes != null) {
            for (String code : requestedCodes) {
                if (code == null || code.isBlank()) {
                    continue;
                }
                // 校验服务器是否存在（来自 server_info 表）
                Server s = serverMapper.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Server>()
                                .eq(Server::getServerCode, code.trim())
                                .last("LIMIT 1"));
                if (s == null) {
                    send(session, ServerMessage.builder()
                            .type("error")
                            .requestId(req.getRequestId())
                            .code("INVALID_SERVER_CODE")
                            .message("Server " + code.trim() + " not found or no permission")
                            .build());
                    continue;
                }
                codes.add(code.trim());
                validServers++;
            }
        }

        if (requestedMetrics != null) {
            for (String m : requestedMetrics) {
                if (METRIC_SERVER.equals(m) || METRIC_DISK.equals(m)) {
                    types.add(m);
                }
            }
        }
        // 默认订阅两种指标类型，若前端未指定 metrics
        if (types.isEmpty()) {
            types.add(METRIC_SERVER);
            types.add(METRIC_DISK);
        }

        send(session, ServerMessage.builder()
                .type("ack")
                .requestId(req.getRequestId())
                .status("ok")
                .message("Subscribed to " + validServers + " servers, " + types.size() + " metric types")
                .build());
    }

    private void handleUnsubscribe(WebSocketSession session, ClientMessage req) {
        Set<String> codes = sessionSubscriptions.get(session.getId());
        Set<String> types = sessionMetricTypes.get(session.getId());
        if (req.getPayload() != null && req.getPayload().getServerCodes() != null && codes != null) {
            req.getPayload().getServerCodes().forEach(c -> codes.remove(c.trim()));
        } else if (codes != null) {
            codes.clear();
        }
        if (types != null) {
            types.clear();
        }
        send(session, ServerMessage.builder()
                .type("ack")
                .requestId(req.getRequestId())
                .status("ok")
                .message("Unsubscribed")
                .build());
    }

    // ===================== 空闲心跳扫描 =====================

    /**
     * 定时扫描：前端超过 60s 未发送 ping 的会话将被主动断开，避免僵尸连接占用资源。
     */
    @Scheduled(fixedDelay = WS_IDLE_SCAN_PERIOD_MS)
    public void scanIdleSessions() {
        long now = System.currentTimeMillis();
        long deadline = now - WS_PING_TIMEOUT_MS;
        for (Map.Entry<String, Long> entry : sessionLastPing.entrySet()) {
            Long lastPing = entry.getValue();
            if (lastPing == null || lastPing < deadline) {
                String sessionId = entry.getKey();
                WebSocketSession session = sessionCache.get(sessionId);
                log.warn("[WS] 前端心跳超时（>{}s 未收到 ping），主动断开: {}",
                        WS_PING_TIMEOUT_MS / 1000, sessionId);
                if (session != null) {
                    try {
                        session.close(CloseStatus.SESSION_NOT_RELIABLE.withReason("heartbeat timeout"));
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    // ===================== 定时推送 =====================

    /**
     * 定时从 InfluxDB 拉取所有被订阅服务器的最新指标并推送。
     * 推送周期由配置 ws.push-interval-ms 决定（默认 1s，代码默认值，不依赖外部配置）。
     */
    @Scheduled(fixedDelayString = "${ws.push-interval-ms:1000}")
    public void pushMetrics() {
        if (sessionSubscriptions.isEmpty()) {
            return;
        }
        InfluxDBClient client = influxDBClientProvider.getIfAvailable();
        if (client == null) {
            return;
        }

        // 汇总所有会话订阅的服务器编码
        Set<String> allSubscribed = new HashSet<>();
        sessionSubscriptions.values().forEach(allSubscribed::addAll);
        if (allSubscribed.isEmpty()) {
            return;
        }

        Set<String> subscribedTypes = new HashSet<>();
        sessionMetricTypes.values().forEach(subscribedTypes::addAll);
        boolean needServer = subscribedTypes.contains(METRIC_SERVER);
        boolean needDisk = subscribedTypes.contains(METRIC_DISK);

        QueryApi queryApi = client.getQueryApi();
        String bucket = influxConfig.getBucket();

        for (String serverCode : allSubscribed) {
            if (needServer) {
                Map<String, Object> metric = queryLatestServerMetric(queryApi, bucket, serverCode);
                if (metric != null) {
                    broadcast(serverCode, METRIC_SERVER, metric, null);
                }
            }
            if (needDisk) {
                List<Map<String, Object>> disks = queryLatestDiskMetrics(queryApi, bucket, serverCode);
                if (disks != null && !disks.isEmpty()) {
                    broadcast(serverCode, METRIC_DISK, null, disks);
                }
            }
        }
    }

    private Map<String, Object> queryLatestServerMetric(QueryApi queryApi, String bucket, String serverCode) {
        String flux = "from(bucket: \"" + bucket + "\") "
                + "|> range(start: -5m) "
                + "|> filter(fn: (r) => r._measurement == \"server_metrics\" "
                + "and r.server_code == \"" + fluxEscape(serverCode) + "\") "
                + "|> last()";
        try {
            List<FluxTable> tables = queryApi.query(flux);
            Map<String, Object> data = new LinkedHashMap<>();
            Long timeMs = null;
            for (FluxTable table : tables) {
                for (FluxRecord rec : table.getRecords()) {
                    if (timeMs == null && rec.getTime() != null) {
                        timeMs = rec.getTime().toEpochMilli();
                    }
                    data.put(rec.getField(), toNumber(rec.getValue()));
                }
            }
            if (data.isEmpty()) {
                return null;
            }
            // 补充 hostname（InfluxDB 中 server_metrics 以 hostname 为 tag）
            if (!data.containsKey("hostname")) {
                attachTag(data, queryApi, bucket, serverCode, "server_metrics", "hostname");
            }
            data.put("@time", timeMs);
            return data;
        } catch (Exception e) {
            log.warn("[WS] 查询 server_metrics 失败 {}: {}", serverCode, e.getMessage());
            return null;
        }
    }

    private List<Map<String, Object>> queryLatestDiskMetrics(QueryApi queryApi, String bucket, String serverCode) {
        String flux = "from(bucket: \"" + bucket + "\") "
                + "|> range(start: -5m) "
                + "|> filter(fn: (r) => r._measurement == \"server_disk_metrics\" "
                + "and r.server_code == \"" + fluxEscape(serverCode) + "\") "
                + "|> last()";
        try {
            List<FluxTable> tables = queryApi.query(flux);
            // mount_point → 该盘各字段
            Map<String, Map<String, Object>> byMount = new LinkedHashMap<>();
            Long timeMs = null;
            for (FluxTable table : tables) {
                for (FluxRecord rec : table.getRecords()) {
                    Object mp = rec.getValueByKey("mount_point");
                    if (mp == null) {
                        continue;
                    }
                    if (timeMs == null && rec.getTime() != null) {
                        timeMs = rec.getTime().toEpochMilli();
                    }
                    byMount.computeIfAbsent(mp.toString(), k -> new LinkedHashMap<>())
                            .put(rec.getField(), toNumber(rec.getValue()));
                }
            }
            List<Map<String, Object>> result = new java.util.ArrayList<>();
            for (Map.Entry<String, Map<String, Object>> e : byMount.entrySet()) {
                Map<String, Object> disk = e.getValue();
                disk.put("mount_point", e.getKey());
                disk.put("@time", timeMs);
                result.add(disk);
            }
            return result;
        } catch (Exception e) {
            log.warn("[WS] 查询 server_disk_metrics 失败 {}: {}", serverCode, e.getMessage());
            return List.of();
        }
    }

    private void attachTag(Map<String, Object> data, QueryApi queryApi, String bucket,
                           String serverCode, String measurement, String tag) {
        String flux = "from(bucket: \"" + bucket + "\") "
                + "|> range(start: -5m) "
                + "|> filter(fn: (r) => r._measurement == \"" + measurement + "\" "
                + "and r.server_code == \"" + fluxEscape(serverCode) + "\") "
                + "|> last()";
        try {
            List<FluxTable> tables = queryApi.query(flux);
            for (FluxTable table : tables) {
                for (FluxRecord rec : table.getRecords()) {
                    Object v = rec.getValueByKey(tag);
                    if (v != null) {
                        data.put(tag, v);
                        return;
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 通知订阅了 serverCode 的客户端监控配置发生变化。
     * actionType 必须为大写 POST、PUT 或 DEL，删除通知固定携带 status=0。
     */
    public void notifyMonitorConfigChanged(MonitorServiceConfig config, String actionType) {
        if (config == null || config.getServerCode() == null
                || !Set.of("POST", "PUT", "DEL").contains(actionType)) {
            log.warn("[WS] 忽略非法监控配置变更通知: serverCode={}, actionType={}",
                    config == null ? null : config.getServerCode(), actionType);
            return;
        }
        Integer status = "DEL".equals(actionType) ? 0 : config.getStatus();
        if (status == null || (status != 0 && status != 1)) {
            log.warn("[WS] 忽略非法监控配置状态: serverCode={}, status={}", config.getServerCode(), status);
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", config.getId());
        data.put("serverCode", config.getServerCode());
        data.put("serviceName", config.getServiceName());
        data.put("processName", config.getProcessName());
        data.put("port", config.getPort());
        data.put("checkInterval", config.getCheckInterval());
        data.put("status", status);

        ServerMessage message = ServerMessage.builder()
                .type("monitor_config_changed")
                .serverCode(config.getServerCode())
                .status(status)
                .actionType(actionType)
                .data(data)
                .build();
        for (Map.Entry<String, Set<String>> entry : sessionSubscriptions.entrySet()) {
            if (!entry.getValue().contains(config.getServerCode())) {
                continue;
            }
            WebSocketSession session = findSession(entry.getKey());
            if (session != null && session.isOpen()) {
                send(session, message);
            }
        }
    }
    // ===================== 广播 =====================

    /**
     * 向订阅了指定 serverCode 的所有会话推送指标。
     * serverData 非空表示基础指标；diskData 非空表示磁盘指标列表。
     */
    private void broadcast(String serverCode, String type,
                           Map<String, Object> serverData, List<Map<String, Object>> diskData) {
        Long timeMs;
        if (serverData != null) {
            timeMs = (Long) serverData.remove("@time");
        } else {
            timeMs = (Long) diskData.get(0).remove("@time");
        }
        String ts = ISO_FMT.format(Instant.ofEpochMilli(timeMs == null ? System.currentTimeMillis() : timeMs));

        for (Map.Entry<String, Set<String>> entry : sessionSubscriptions.entrySet()) {
            if (!entry.getValue().contains(serverCode)) {
                continue;
            }
            WebSocketSession session = findSession(entry.getKey());
            if (session == null || !session.isOpen()) {
                continue;
            }
            ServerMessage msg = ServerMessage.builder()
                    .type(type)
                    .serverCode(serverCode)
                    .timestamp(ts)
                    .data(serverData)
                    .dataList(diskData)
                    .build();
            send(session, msg);
        }
    }

    // ===================== 工具 =====================

    private WebSocketSession findSession(String sessionId) {
        // 维护 id→session 映射代价高，这里借助 Spring 的 session 管理：直接遍历已注册 sessions
        // 简化实现：通过 WebSocketHandler 的 session 集合不可直接访问，采用本地弱引用缓存
        return sessionCache.get(sessionId);
    }

    /** sessionId → session 缓存（afterConnectionEstablished 时写入） */
    private final Map<String, WebSocketSession> sessionCache = new ConcurrentHashMap<>();

    private void send(WebSocketSession session, ServerMessage msg) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(JSONUtil.toJsonStr(msg)));
        } catch (IOException e) {
            log.warn("[WS] 发送消息失败 {}: {}", session.getId(), e.getMessage());
        }
    }

    private String extractSocketToken(WebSocketSession session) {
        String q = session.getUri() == null ? null : session.getUri().getQuery();
        if (q == null) {
            return null;
        }
        for (String pair : q.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && "SocketToken".equalsIgnoreCase(kv[0])) {
                return kv[1];
            }
        }
        return null;
    }

    private static Object toNumber(Object v) {
        if (v instanceof Number n) {
            if (v instanceof Double || v instanceof Float) {
                return n.doubleValue();
            }
            return n;
        }
        return v;
    }

    private static String fluxEscape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
