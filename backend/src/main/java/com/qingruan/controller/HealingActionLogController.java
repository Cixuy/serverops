package com.qingruan.controller;

import com.qingruan.common.result.PageResult;
import com.qingruan.common.result.Result;
import com.qingruan.dto.request.HealingActionLogCreateRequest;
import com.qingruan.service.HealingActionLogService;
import com.qingruan.vo.HealingActionLogCreateResponse;
import com.qingruan.vo.HealingActionLogInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@Slf4j
@RestController
@RequestMapping("/healing-action-logs")
@RequiredArgsConstructor
@Tag(name = "13-自愈日志管理", description = "自愈操作日志管理")
public class HealingActionLogController {

    private final HealingActionLogService healingActionLogService;

    @Operation(summary = "新增自愈操作日志", description = "管理端手动新增或补录一条自愈操作日志记录，落库至 healing_action_log 表")
    @PostMapping
    public Result<HealingActionLogCreateResponse> createHealingActionLog(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "新增自愈操作日志请求")
            @Valid @RequestBody HealingActionLogCreateRequest request) {
        log.info("新增自愈操作日志请求: {}", request);
        HealingActionLogCreateResponse response = healingActionLogService.createHealingActionLog(request);
        return Result.<HealingActionLogCreateResponse>error(200, "success").setData(response);
    }

    @Operation(summary = "分页查询自愈操作日志", description = "分页查询自愈操作日志，支持按关联告警ID、服务器编码、监控配置ID、自愈规则ID、执行状态及执行时间范围筛选")
    @GetMapping
    public Result<PageResult<HealingActionLogInfo>> listHealingActionLogs(
            @Parameter(description = "页码", example = "1")
            @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页数量", example = "20")
            @RequestParam(defaultValue = "20") Integer pageSize,
            @Parameter(description = "关联告警ID")
            @RequestParam(required = false) Long alertId,
            @Parameter(description = "服务器编码")
            @RequestParam(required = false) String serverCode,
            @Parameter(description = "监控配置ID")
            @RequestParam(required = false) Long configId,
            @Parameter(description = "自愈规则ID")
            @RequestParam(required = false) Long ruleId,
            @Parameter(description = "执行状态: SUCCESS / FAILED / TIMEOUT / RETRYING")
            @RequestParam(required = false) String executionStatus,
            @Parameter(description = "开始时间下限 (date-time)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @Parameter(description = "开始时间上限 (date-time)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        PageResult<HealingActionLogInfo> page = healingActionLogService.listHealingActionLogs(
                pageNum, pageSize, alertId, serverCode, configId, ruleId, executionStatus, startTime, endTime);
        return Result.ok(page);
    }
}
