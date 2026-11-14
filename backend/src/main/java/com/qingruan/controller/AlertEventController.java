package com.qingruan.controller;

import com.qingruan.common.constant.SystemCodeEnum;
import com.qingruan.common.result.PageResult;
import com.qingruan.common.result.Result;
import com.qingruan.common.constant.AlertActionResult;
import com.qingruan.dto.request.AlertEventReportRequest;
import com.qingruan.service.AlertEventService;
import com.qingruan.vo.AlertActionRequest;
import com.qingruan.vo.AlertDetailResponse;
import com.qingruan.vo.AlertEventInfo;
import com.qingruan.vo.AlertEventReportResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/alert-events")
@RequiredArgsConstructor
@Tag(name = "9-告警管理", description = "告警管理")
@Slf4j
public class AlertEventController {

    private final AlertEventService alertEventService;

    @Operation(summary = "获取告警事件列表", description = "分页查询告警事件列表，支持按服务器、级别、状态及时间范围筛选")
    @GetMapping
    public Result<PageResult<AlertEventInfo>> getAlertEventList(
            @Parameter(description = "页码", example = "1")
            @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页数量", example = "10")
            @RequestParam(defaultValue = "10") Integer pageSize,
            @Parameter(description = "服务器编码")
            @RequestParam(required = false) String serverCode,
            @Parameter(description = "告警级别: INFO / WARNING / CRITICAL")
            @RequestParam(required = false) String alertLevel,
            @Parameter(description = "告警状态: PENDING / HEALING / RESOLVED / IGNORED")
            @RequestParam(required = false) String alertStatus,
            @Parameter(description = "触发时间下限 (date-time)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @Parameter(description = "触发时间上限 (date-time)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        PageResult<AlertEventInfo> page = alertEventService.getAlertEventList(
                pageNum, pageSize, serverCode, alertLevel, alertStatus, startTime, endTime);
        return Result.ok(page);
    }

    @Operation(summary = "上报告警事件", description = "Agent/监控组件上报告警事件，生成告警编号并持久化，返回告警ID、编号及状态")
    @PostMapping
    public Result<AlertEventReportResponse> reportAlertEvent(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "上报告警事件请求")
            @Valid @RequestBody AlertEventReportRequest request) {
        log.info("上报告警事件请求: {}", request);
        AlertEventReportResponse response = alertEventService.reportAlertEvent(request);
        return Result.<AlertEventReportResponse>error(200, "上报成功").setData(response);
    }

    @Operation(summary = "获取告警详情及处置时间线", description = "根据ID获取告警详情及其处置操作流水")
    @GetMapping("/{id}")
    public Result<AlertDetailResponse> getAlertDetail(
            @Parameter(description = "告警事件ID", example = "1")
            @PathVariable("id") Long id) {
        AlertDetailResponse detail = alertEventService.getAlertDetail(id);
        if (detail == null) {
            return Result.error(SystemCodeEnum.NOT_FOUND);
        }
        return Result.ok(detail);
    }

    @Operation(summary = "告警处理操作", description = "对告警执行自愈、手动恢复或搁置操作")
    @PostMapping("/{id}/action")
    public Result<Void> performAction(
            @Parameter(description = "告警事件ID", example = "1")
            @PathVariable("id") Long id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "处理操作请求")
            @RequestBody AlertActionRequest request) {
        String actionType = request.getActionType();
        if (actionType == null || !Set.of("AUTO_HEAL", "MANUAL_RECOVER", "IGNORE").contains(actionType)) {
            return Result.error(SystemCodeEnum.BAD_REQUEST);
        }
        AlertActionResult result = alertEventService.performAction(id, request);
        if (result == AlertActionResult.NOT_FOUND) {
            return Result.error(SystemCodeEnum.NOT_FOUND);
        }
        if (result == AlertActionResult.ALREADY_CLOSED) {
            return Result.error(SystemCodeEnum.ALERT_ALREADY_CLOSED);
        }
        return Result.ok();
    }
}
