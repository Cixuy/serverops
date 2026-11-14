package com.qingruan.websocket.frontend.session;

import com.qingruan.websocket.common.session.WebSocketSessionManager;
import org.springframework.stereotype.Component;

/**
 * 前端专用会话管理器。
 * <p>
 * 复用公共 {@link WebSocketSessionManager}，固定连接类型为 FRONTEND，
 * 并对外暴露前端语义友好的发送方法。
 */
@Component
public class FrontendSessionManager extends WebSocketSessionManager {

    /** 注册前端会话（固定连接类型为 FRONTEND） */
    public void register(String sessionId, org.springframework.web.socket.WebSocketSession session,
                         String serverCode) {
        super.register(sessionId, session, WebSocketSessionManager.SessionType.FRONTEND, serverCode);
    }

    /** 向指定 serverCode 的所有前端会话推送 */
    public void sendToServer(String serverCode, String payload) {
        super.sendToServer(serverCode, SessionType.FRONTEND, payload);
    }

    /** 向所有前端会话广播 */
    public void broadcast(String payload) {
        super.broadcast(SessionType.FRONTEND, payload);
    }
}
