package com.qingruan.websocket.common.exception;

import com.qingruan.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * WebSocket 模块统一异常处理。
 * <p>
 * 主要捕获握手 / 消息处理阶段抛出的业务异常并转换为统一的 {@link Result} 结构。
 * 注意：已建立的 WebSocket 连接上的异常无法经由 HTTP 响应返回，应在 Handler 内部直接
 * 通过会话通道下发错误帧；本 advice 面向握手（HTTP 升级）阶段可感知的异常。
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.qingruan.websocket")
public class WsExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Object> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("[WS] 参数校验失败: {}", e.getMessage());
        return Result.error(400, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<Object> handleGeneric(Exception e) {
        log.error("[WS] 未预期异常", e);
        return Result.error(500, "WebSocket 服务异常");
    }
}
