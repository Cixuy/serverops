package com.qingruan.websocket.client.handler;

import com.qingruan.dto.response.AgentMonitorConfig;
import com.qingruan.websocket.client.model.ClientMonitorConfig;
import com.qingruan.websocket.client.model.ClientRequest;
import com.qingruan.websocket.client.model.ClientResponse;
import com.qingruan.websocket.client.session.ClientSessionManager;
import com.qingruan.websocket.common.util.WsUtil;
import com.qingruan.service.MonitorServiceConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 客户端 / 设备 WebSocket 处理器，对应端点 /ws/client。
 * <p>
 * 协议（设备 → 后端）：PULL_MONITOR_CONFIG（请求下发监控配置）<br>
 * 协议（后端 → 设备）：PULL_MONITOR_CONFIG_RESP / error / pong
 * <p>
 * 鉴权由 {@code DeviceAuthInterceptor} 在握手阶段完成。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClientWebSocketHandler extends TextWebSocketHandler {

    private final ClientSessionManager clientSessionManager;
    private final MonitorServiceConfigService monitorServiceConfigService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        clientSessionManager.register(session.getId(), session,
                (String) session.getAttributes().get("serverCode"));
        log.info("[WS-CLIENT] 设备连接建立: {} (serverCode={})",
                session.getId(), session.getAttributes().get("serverCode"));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ClientRequest req = WsUtil.parse(message.getPayload(), ClientRequest.class);
        if (req == null || req.getAction() == null) {
            send(session, ClientResponse.builder()
                    .action("error").requestId(null)
                    .code("INVALID_ACTION").message("action 不能为空").build());
            return;
        }
        switch (req.getAction()) {
            case "PULL_MONITOR_CONFIG" -> handlePullMonitorConfig(session, req);
            case "ping" -> send(session, ClientResponse.builder()
                    .action("pong").requestId(req.getRequestId()).build());
            default -> send(session, ClientResponse.builder()
                    .action("error").requestId(req.getRequestId())
                    .code("UNKNOWN_ACTION").message("未知 action: " + req.getAction()).build());
        }
    }

    private void handlePullMonitorConfig(WebSocketSession session, ClientRequest req) {
        String serverCode = req.getPayload() != null ? req.getPayload().getServerCode() : null;
        if (serverCode == null || serverCode.isBlank()) {
            send(session, ClientResponse.builder()
                    .action("error").requestId(req.getRequestId())
                    .code("INVALID_PARAM").message("server_code 不能为空").build());
            return;
        }
        serverCode = serverCode.trim();
        // 客户端在此明确身份：将会话关联到其声明的 serverCode，
        // 以便后续定向推送（如自愈规则绑定变更）能正确命中本会话。
        clientSessionManager.associateServerCode(session.getId(), serverCode);
        try {
            List<AgentMonitorConfig> configs =
                    monitorServiceConfigService.getConfigsByServerCode(serverCode.trim());
            List<ClientMonitorConfig> data = configs.stream().map(this::toClientConfig).collect(Collectors.toList());
            send(session, ClientResponse.builder()
                    .action("PULL_MONITOR_CONFIG_RESP").requestId(req.getRequestId()).data(data).build());
        } catch (IllegalArgumentException e) {
            send(session, ClientResponse.builder()
                    .action("error").requestId(req.getRequestId())
                    .code("SERVER_NOT_FOUND").message("服务器不存在: " + serverCode.trim()).build());
        }
    }

    private ClientMonitorConfig toClientConfig(AgentMonitorConfig cfg) {
        ClientMonitorConfig c = new ClientMonitorConfig();
        c.setConfigId(cfg.getConfigId());
        c.setServiceName(cfg.getServiceName());
        c.setProcessName(cfg.getProcessName());
        c.setPort(cfg.getPort());
        c.setCheckInterval(cfg.getCheckInterval());
        return c;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        clientSessionManager.remove(session.getId());
        log.info("[WS-CLIENT] 设备连接关闭: {} ({})", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("[WS-CLIENT] 传输异常: {} ({})", session.getId(), exception.getMessage());
    }

    private void send(WebSocketSession session, ClientResponse resp) {
        clientSessionManager.send(session, WsUtil.toJson(resp));
    }
}
