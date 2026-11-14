package com.qingruan.common.config;

import com.qingruan.handler.MetricsWebSocketHandler;
import com.qingruan.websocket.client.handler.ClientWebSocketHandler;
import com.qingruan.websocket.client.interceptor.DeviceAuthInterceptor;
import com.qingruan.websocket.common.interceptor.AuthHandshakeInterceptor;
import com.qingruan.websocket.frontend.handler.FrontendWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置：注册各类实时通信端点
 * <p>
 * 注意：application.yml 已配置 server.servlet.context-path=/api/v1，
 * Spring Boot 会为所有端点自动补全该前缀，故对外完整地址为：
 * ws://{host}:{port}/api/v1/ws/realtime
 * ws://{host}:{port}/api/v1/ws/frontend  （前端订阅实时指标）
 * ws://{host}:{port}/api/v1/ws/client     （客户端监听监控配置）
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final MetricsWebSocketHandler metricsWebSocketHandler;
    private final FrontendWebSocketHandler frontendWebSocketHandler;
    private final ClientWebSocketHandler clientWebSocketHandler;
    private final AuthHandshakeInterceptor authHandshakeInterceptor;
    private final DeviceAuthInterceptor deviceAuthInterceptor;

    public WebSocketConfig(MetricsWebSocketHandler metricsWebSocketHandler,
                           FrontendWebSocketHandler frontendWebSocketHandler,
                           ClientWebSocketHandler clientWebSocketHandler,
                           AuthHandshakeInterceptor authHandshakeInterceptor,
                           DeviceAuthInterceptor deviceAuthInterceptor) {
        this.metricsWebSocketHandler = metricsWebSocketHandler;
        this.frontendWebSocketHandler = frontendWebSocketHandler;
        this.clientWebSocketHandler = clientWebSocketHandler;
        this.authHandshakeInterceptor = authHandshakeInterceptor;
        this.deviceAuthInterceptor = deviceAuthInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {

        registry.addHandler(frontendWebSocketHandler, "/ws/frontend")
                .setAllowedOrigins("*")
                .addInterceptors(authHandshakeInterceptor);

        registry.addHandler(clientWebSocketHandler, "/ws/client")
                .setAllowedOrigins("*")
                .addInterceptors(deviceAuthInterceptor);
    }
}
