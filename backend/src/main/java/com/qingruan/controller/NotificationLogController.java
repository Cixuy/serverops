package com.qingruan.controller;

import com.qingruan.common.result.PageResult;
import com.qingruan.common.result.Result;
import com.qingruan.service.NotificationLogService;
import com.qingruan.vo.NotificationLogInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/notification-logs")
@RequiredArgsConstructor
@Tag(name = "10-通知管理", description = "通知管理")
public class NotificationLogController {

    private final NotificationLogService notificationLogService;

    @Operation(summary = "获取邮件通知记录", description = "分页查询邮件通知记录，支持按告警ID与发送状态筛选")
    @GetMapping
    public Result<PageResult<NotificationLogInfo>> getNotificationLogList(
            @Parameter(description = "页码", example = "1")
            @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页数量", example = "10")
            @RequestParam(defaultValue = "10") Integer pageSize,
            @Parameter(description = "关联告警事件ID")
            @RequestParam(required = false) Long alertId,
            @Parameter(description = "发送状态: 0-失败 1-成功")
            @RequestParam(required = false) Integer sendStatus) {
        return Result.ok(notificationLogService.getNotificationLogList(pageNum, pageSize, alertId, sendStatus));
    }
}
