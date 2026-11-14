package com.qingruan.common.security;

/**
 * WebSocket 连接鉴权校验器。
 * <p>
 * SocketToken 本质是用户登录后颁发的认证 token（JWT / 会话 token）。
 * 当前登录模块尚未实现，故提供默认实现仅做开发期放行 / 白名单兼容。
 * <p>
 * 未来登录模块完成后，只需提供一个基于 JWT 解析的实现替换本 Bean 即可，
 * MetricsWebSocketHandler 的鉴权逻辑无需改动。
 */
public interface SocketTokenValidator {

    /**
     * 校验前端连接携带的 SocketToken 是否合法。
     *
     * @param token 连接 query 参数 ?SocketToken=xxx 的值（可能为 null）
     * @return true=允许连接，false=拒绝
     */
    boolean validate(String token);
}
