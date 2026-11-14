package com.qingruan.controller;

import com.qingruan.common.constant.SystemCodeEnum;
import com.qingruan.common.result.Result;
import com.qingruan.dto.request.HeartbeatRequest;
import com.qingruan.dto.request.ServerRegisterRequest;
import com.qingruan.dto.response.ServerRegisterResponse;
import com.qingruan.service.AgentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;

/**
 * Agent 接入接口: 注册 / 心跳
 */
@RestController
@RequestMapping("/servers")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    /**
     * POST /api/v1/servers/register
     * Agent 首次启动时调用，注册服务器基础信息并获取 server_code
     */
    @PostMapping("/register")
    public Result<ServerRegisterResponse> register(@Valid @RequestBody ServerRegisterRequest request) {
        return Result.ok(agentService.register(request));
    }

    /**
     * POST /api/v1/servers/heartbeat
     * Agent 定时(如30秒)上报心跳，维持在线状态
     */
    @PostMapping("/heartbeat")
    public Result<Object> heartbeat(@Valid @RequestBody HeartbeatRequest request) {
        try {
            agentService.heartbeat(request);
            return Result.ok(Collections.emptyMap());
        } catch (IllegalArgumentException e) {
            // serverCode 在数据库中不存在 -> 返回「无服务器」错误，而非 success
            return Result.error(SystemCodeEnum.SERVER_NOT_FOUND, e.getMessage());
        }
    }
}
