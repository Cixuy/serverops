package com.qingruan.common.security;

import org.springframework.stereotype.Component;

/**
 * 开发期 / 登录未接入时的 WebSocket token 校验实现。
 * <p>
 * 当前登录模块尚未实现，故直接放行所有连接（开发期联调友好）。
 * <p>
 * 注意：这不是最终的登录态校验。登录模块实现后，应提供基于 JWT 的
 * SocketTokenValidator 实现（解析 token、校验签名与过期、提取用户身份），
 * 并通过 @Primary 或条件装配替换本实现。
 */
@Component
public class DevSocketTokenValidator implements SocketTokenValidator {

    @Override
    public boolean validate(String token) {
        // 登录未接入，开发期放行所有连接
        return true;
    }
}
