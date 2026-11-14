package com.qingruan.controller;

import com.qingruan.common.constant.SystemCodeEnum;
import com.qingruan.common.result.PageResult;
import com.qingruan.common.result.Result;
import com.qingruan.entity.ServerInfo;
import com.qingruan.service.ServerInfoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/servers")
@RequiredArgsConstructor
@Tag(name = "3-服务器管理", description = "服务器管理")
public class ServerController {

    private final ServerInfoService serverInfoService;

    @Operation(summary = "获取服务器列表", description = "分页查询服务器列表，支持按状态和关键字筛选")
    @GetMapping
    public Result<PageResult<ServerInfo>> getServerList(
            @Parameter(description = "页码", example = "1")
            @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页数量", example = "10")
            @RequestParam(defaultValue = "10") Integer pageSize,
            @Parameter(description = "状态: 1-在线 0-离线")
            @RequestParam(required = false) Integer status,
            @Parameter(description = "搜索主机名或IP")
            @RequestParam(required = false) String keyword) {
        PageResult<ServerInfo> page = serverInfoService.getServerList(pageNum, pageSize, status, keyword);
        return Result.ok(page);
    }

    @Operation(summary = "获取服务器详情", description = "根据ID获取服务器详情")
    @GetMapping("/{id}")
    public Result<ServerInfo> getServerDetail(
            @Parameter(description = "服务器ID", example = "1")
            @PathVariable("id") Long id) {
        ServerInfo serverInfo = serverInfoService.getServerDetail(id);
        if (serverInfo == null) {
            return Result.error(SystemCodeEnum.NOT_FOUND);
        }
        return Result.ok(serverInfo);
    }

    @Operation(summary = "删除服务器", description = "移除服务器监控记录（同时清理关联的InfluxDB数据）")
    @DeleteMapping("/{id}")
    public Result<Void> deleteServer(
            @Parameter(description = "服务器ID", example = "1")
            @PathVariable("id") Long id) {
        boolean removed = serverInfoService.deleteServer(id);
        if (!removed) {
            return Result.error(SystemCodeEnum.NOT_FOUND);
        }
        return Result.ok();
    }
}
