package com.qingruan.websocket.client.session;

import com.qingruan.websocket.common.session.WebSocketSessionManager;
import org.springframework.stereotype.Component;

/**
 * 客户端 / 设备专用会话管理器。
 * <p>
 * 复用公共 {@link WebSocketSessionManager}，固定连接类型为 CLIENT，
 * 便于服务端向指定设备反向下发指令 / 监控配置。
 */
@Component
public class ClientSessionManager extends WebSocketSessionManager {

    /** 注册客户端会话（固定连接类型为 CLIENT） */
    public void register(String sessionId, org.springframework.web.socket.WebSocketSession session,
                         String serverCode) {
        super.register(sessionId, session, WebSocketSessionManager.SessionType.CLIENT, serverCode);
    }

    /** 向指定 serverCode 的设备会话推送 */
    public void sendToServer(String serverCode, String payload) {
        super.sendToServer(serverCode, SessionType.CLIENT, payload);
    }

    /** 向所有设备会话广播 */
    public void broadcast(String payload) {
        super.broadcast(SessionType.CLIENT, payload);
    }

    /** 将已注册客户端会话重新关联到其声明的 serverCode（客户端主动声明身份时调用） */
    public void associateServerCode(String sessionId, String serverCode) {
        super.associateServerCode(sessionId, serverCode);
    }
}
