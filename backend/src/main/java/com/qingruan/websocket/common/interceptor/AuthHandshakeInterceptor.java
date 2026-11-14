package com.qingruan.websocket.common.interceptor;

import com.qingruan.common.security.SocketTokenValidator;
import com.qingruan.websocket.common.util.WsUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * 通用握手鉴权拦截器（前端 Web 连接使用）。
 * <p>
 * 从握手请求的 query 参数中读取 SocketToken，委托 {@link SocketTokenValidator} 校验。
 * 校验通过后将 serverCode / userId 等信息写入会话属性，供后续 Handler 使用；
 * 校验失败则中断握手（返回 false），连接不会被建立。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthHandshakeInterceptor implements HandshakeInterceptor {

    private final SocketTokenValidator tokenValidator;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = WsUtil.getQueryParam(request, "SocketToken");
        if (!tokenValidator.validate(token)) {
            log.warn("[WS] 前端握手被拒绝：SocketToken 无效");
            return false;
        }
        // 透传可用于业务识别的参数到会话属性
        String serverCode = WsUtil.getQueryParam(request, "serverCode");
        if (serverCode != null) {
            attributes.put("serverCode", serverCode);
        }
        String userId = WsUtil.getQueryParam(request, "userId");
        if (userId != null) {
            attributes.put("userId", userId);
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 握手成功后无需额外处理
    }
}
