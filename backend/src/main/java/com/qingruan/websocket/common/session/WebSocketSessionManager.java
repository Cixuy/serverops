package com.qingruan.websocket.common.session;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 会话管理器（公共层）。
 * <p>
 * 维护所有 WebSocket 会话，并按连接类型（前端 / 客户端设备）区分管理，
 * 提供按 serverCode 定向推送、按类型遍历、安全发送等通用能力。
 */
@Slf4j
public class WebSocketSessionManager {

    /** 会话类型：frontend=前端Web，client=客户端/设备 */
    public enum SessionType {
        FRONTEND,
        CLIENT
    }

    /** sessionId → 连接类型 */
    private final Map<String, SessionType> sessionTypeMap = new ConcurrentHashMap<>();
    /** sessionId → WebSocketSession */
    private final Map<String, WebSocketSession> sessionMap = new ConcurrentHashMap<>();
    /** serverCode → sessionId 集合（用于定向推送） */
    private final Map<String, Set<String>> serverSessions = new ConcurrentHashMap<>();

    /** 注册会话 */
    public void register(String sessionId, WebSocketSession session, SessionType type, String serverCode) {
        sessionTypeMap.put(sessionId, type);
        sessionMap.put(sessionId, session);
        if (serverCode != null && !serverCode.isBlank()) {
            serverSessions.computeIfAbsent(serverCode, k -> ConcurrentHashMap.newKeySet())
                    .add(sessionId);
        }
    }

    /** 移除会话并清理关联 */
    public void remove(String sessionId) {
        SessionType type = sessionTypeMap.remove(sessionId);
        WebSocketSession session = sessionMap.remove(sessionId);
        if (session != null && session.getAttributes() != null) {
            String serverCode = (String) session.getAttributes().get("serverCode");
            if (serverCode != null) {
                Set<String> ids = serverSessions.get(serverCode);
                if (ids != null) {
                    ids.remove(sessionId);
                    if (ids.isEmpty()) {
                        serverSessions.remove(serverCode);
                    }
                }
            }
        }
    }

    public WebSocketSession get(String sessionId) {
        return sessionMap.get(sessionId);
    }

    public SessionType getType(String sessionId) {
        return sessionTypeMap.get(sessionId);
    }

    public boolean isOpen(String sessionId) {
        WebSocketSession s = sessionMap.get(sessionId);
        return s != null && s.isOpen();
    }

    /** 获取指定类型的所有在线会话 */
    public Set<String> sessionIdsOfType(SessionType type) {
        Set<String> result = ConcurrentHashMap.newKeySet();
        sessionTypeMap.forEach((id, t) -> {
            if (t == type && isOpen(id)) {
                result.add(id);
            }
        });
        return result;
    }

    /**
     * 将已注册会话重新关联到新的 serverCode。
     * <p>
     * 用于客户端在连接后通过业务消息（如 PULL_MONITOR_CONFIG）才声明自身 serverCode 的场景：
     * 先从其它 serverCode 映射集合中移除该会话，再加入新的 serverCode 集合，
     * 保证定向推送能按正确的 serverCode 命中。
     */
    public void associateServerCode(String sessionId, String newServerCode) {
        if (newServerCode == null || newServerCode.isBlank()) {
            return;
        }
        for (Set<String> ids : serverSessions.values()) {
            ids.remove(sessionId);
        }
        serverSessions.computeIfAbsent(newServerCode, k -> ConcurrentHashMap.newKeySet()).add(sessionId);
    }

    /** 向指定 serverCode 下某类型的所有会话推送消息 */
    public void sendToServer(String serverCode, SessionType type, String payload) {
        Set<String> ids = serverSessions.get(serverCode);
        if (ids == null) {
            return;
        }
        for (String id : ids) {
            if (getType(id) == type && isOpen(id)) {
                send(get(id), payload);
            }
        }
    }

    /** 向指定类型的所有会话广播 */
    public void broadcast(SessionType type, String payload) {
        for (String id : sessionIdsOfType(type)) {
            send(get(id), payload);
        }
    }

    /** 安全发送文本消息：自动处理会话关闭 / IO 异常 */
    public void send(WebSocketSession session, String payload) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new org.springframework.web.socket.TextMessage(payload));
        } catch (IOException e) {
            log.warn("[WS] 发送消息失败 session={}: {}", session.getId(), e.getMessage());
        }
    }
}
