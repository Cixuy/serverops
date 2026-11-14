package com.qingruan.controller;

import com.qingruan.common.result.Result;
import com.qingruan.dto.response.PageResultServiceStatusSnapshot;
import com.qingruan.dto.response.ServiceStatusDashboard;
import com.qingruan.service.ServiceStatusSnapshotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 8-服务状态看板接口
 */
@RestController
@RequestMapping("/service-status")
@RequiredArgsConstructor
@Tag(name = "8-服务状态看板", description = "服务状态快照查询与看板统计")
public class ServiceStatusController {

    private final ServiceStatusSnapshotService serviceStatusSnapshotService;

    /**
     * GET /api/v1/service-status/snapshots
     * 获取服务状态快照列表（分页）
     */
    @Operation(summary = "获取服务状态快照列表", description = "分页查询服务状态快照，支持按服务器、存活状态、关键字筛选")
    @GetMapping("/snapshots")
    public Result<PageResultServiceStatusSnapshot> getSnapshots(
            @RequestParam(value = "pageNum", defaultValue = "1") Integer pageNum,
            @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize,
            @RequestParam(value = "serverCode", required = false) String serverCode,
            @RequestParam(value = "isAlive", required = false) Integer isAlive,
            @RequestParam(value = "keyword", required = false) String keyword) {
        PageResultServiceStatusSnapshot result =
                serviceStatusSnapshotService.querySnapshots(pageNum, pageSize, serverCode, isAlive, keyword);
        return Result.ok(result);
    }

    /**
     * GET /api/v1/service-status/dashboard
     * 获取服务状态看板统计（全局或指定服务器）
     */
    @Operation(summary = "获取服务状态看板统计", description = "统计正常/异常服务数量，不传 serverCode 则统计全局")
    @GetMapping("/dashboard")
    public Result<ServiceStatusDashboard> getDashboard(
            @RequestParam(value = "serverCode", required = false) String serverCode) {
        ServiceStatusDashboard dashboard = serviceStatusSnapshotService.getDashboard(serverCode);
        return Result.ok(dashboard);
    }
}
