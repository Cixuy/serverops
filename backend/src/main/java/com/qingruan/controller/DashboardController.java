package com.qingruan.controller;

import com.qingruan.common.result.Result;
import com.qingruan.service.DashboardService;
import com.qingruan.vo.DashboardSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
@Tag(name = "5-数据大屏", description = "数据大屏")
public class DashboardController {

    private final DashboardService dashboardService;

    @Operation(summary = "获取大屏统计概览", description = "获取在线/离线服务器数量等全局统计数据")
    @GetMapping("/summary")
    public Result<DashboardSummary> getDashboardSummary() {
        return Result.ok(dashboardService.getDashboardSummary());
    }
}
