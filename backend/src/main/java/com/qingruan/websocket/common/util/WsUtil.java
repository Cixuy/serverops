package com.qingruan.websocket.common.util;

import cn.hutool.json.JSONUtil;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;

/**
 * WebSocket 通用工具类。
 */
public final class WsUtil {

    private WsUtil() {
    }

    /** 从握手请求 URI 的 query 中读取指定参数 */
    public static String getQueryParam(ServerHttpRequest request, String name) {
        URI uri = request.getURI();
        if (uri == null || uri.getQuery() == null) {
            return null;
        }
        return getQueryParam(uri.getQuery(), name);
    }

    /** 从已建立会话的 URI 的 query 中读取指定参数 */
    public static String getQueryParam(WebSocketSession session, String name) {
        URI uri = session.getUri();
        if (uri == null || uri.getQuery() == null) {
            return null;
        }
        return getQueryParam(uri.getQuery(), name);
    }

    private static String getQueryParam(String query, String name) {
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && name.equalsIgnoreCase(kv[0])) {
                return kv[1];
            }
        }
        return null;
    }

    /** 将对象序列化为 JSON 文本（Hutool） */
    public static String toJson(Object obj) {
        return JSONUtil.toJsonStr(obj);
    }

    /** 安全解析 JSON 为指定类型，失败返回 null */
    public static <T> T parse(String payload, Class<T> clazz) {
        try {
            return JSONUtil.toBean(payload, clazz);
        } catch (Exception e) {
            return null;
        }
    }
}
