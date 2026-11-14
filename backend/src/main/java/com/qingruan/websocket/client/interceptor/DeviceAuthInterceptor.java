package com.qingruan.websocket.client.interceptor;

import com.qingruan.websocket.common.util.WsUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * 设备专用握手鉴权拦截器（客户端 / 设备连接使用）。
 * <p>
 * 校验 query 参数 {@code apiKey}（设备接入密钥）。
 * 当配置了 {@code websocket.client.api-key} 时执行严格校验；未配置时开发期放行，
 * 避免阻塞联调。校验通过将 serverCode 写入会话属性供后续 Handler 使用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceAuthInterceptor implements HandshakeInterceptor {

    @Value("${websocket.client.api-key:}")
    private String expectedApiKey;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String apiKey = WsUtil.getQueryParam(request, "apiKey");
        if (expectedApiKey != null && !expectedApiKey.isBlank()) {
            if (apiKey == null || !expectedApiKey.equals(apiKey)) {
                log.warn("[WS-CLIENT] 握手被拒绝：apiKey 校验失败");
                return false;
            }
        } else {
            log.debug("[WS-CLIENT] 未配置 apiKey，开发期放行");
        }
        String serverCode = WsUtil.getQueryParam(request, "serverCode");
        if (serverCode != null) {
            attributes.put("serverCode", serverCode);
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需额外处理
    }
}
