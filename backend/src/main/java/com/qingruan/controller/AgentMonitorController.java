package com.qingruan.controller;

import com.qingruan.common.constant.SystemCodeEnum;
import com.qingruan.common.result.Result;
import com.qingruan.dto.request.ServiceStatusReportRequest;
import com.qingruan.dto.response.AgentMonitorConfig;
import com.qingruan.service.MonitorServiceConfigService;
import com.qingruan.service.ServiceStatusSnapshotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;

/**
 * Agent 监控接入接口: 拉取监控配置 / 上报服务状态
 */
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "2-Agent接入", description = "Agent 注册、心跳与监控上报")
public class AgentMonitorController {

    private final MonitorServiceConfigService monitorServiceConfigService;
    private final ServiceStatusSnapshotService serviceStatusSnapshotService;

    /**
     * GET /api/v1/agent/monitor-configs
     * Agent 启动或定时拉取服务端下发的监控任务配置
     */
    @Operation(summary = "拉取监控配置", description = "Agent 启动或定时拉取服务端下发的监控任务配置")
    @GetMapping("/monitor-configs")
    public Result<List<AgentMonitorConfig>> getMonitorConfigs(@RequestParam("serverCode") String serverCode) {
        try {
            List<AgentMonitorConfig> configs = monitorServiceConfigService.getConfigsByServerCode(serverCode);
            return Result.ok(configs);
        } catch (IllegalArgumentException e) {
            log.warn("拉取监控配置失败: {}", e.getMessage());
            return Result.error(SystemCodeEnum.SERVER_NOT_FOUND.getCode(), "服务器不存在");
        }
    }

    /**
     * POST /api/v1/agent/service-status/report
     * Agent 定期上报端口探测与进程存活监测结果
     */
    @Operation(summary = "上报服务状态", description = "Agent 定期上报端口探测与进程存活监测结果")
    @PostMapping("/service-status/report")
    public Result<Object> reportServiceStatus(@Valid @RequestBody ServiceStatusReportRequest request) {
        serviceStatusSnapshotService.reportServiceStatus(request);
        return Result.ok(Collections.emptyMap());
    }
}
