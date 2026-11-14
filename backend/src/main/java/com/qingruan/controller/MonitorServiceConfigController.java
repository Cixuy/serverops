package com.qingruan.controller;

import com.qingruan.common.constant.SystemCodeEnum;
import com.qingruan.common.result.PageResult;
import com.qingruan.common.result.Result;
import com.qingruan.dto.ServiceConfigCreateRequest;
import com.qingruan.dto.ServiceConfigUpdateRequest;
import com.qingruan.entity.MonitorServiceConfig;
import com.qingruan.service.MonitorServiceConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/monitor-configs")
@RequiredArgsConstructor
@Tag(name = "7-服务配置管理", description = "监控服务配置管理")
public class MonitorServiceConfigController {

    private final MonitorServiceConfigService service;

    @GetMapping
    @Operation(summary = "获取监控配置列表")
    public Result<PageResult<MonitorServiceConfig>> page(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @Parameter(description = "服务器唯一编码") @RequestParam(required = false) String serverCode,
            @Parameter(description = "状态：1-启用，0-停用") @RequestParam(required = false) Integer status,
            @Parameter(description = "模糊搜索服务名称") @RequestParam(required = false) String keyword) {
        return Result.ok(service.page(pageNum, pageSize, serverCode, keyword, status));
    }

    @PostMapping
    @Operation(summary = "新增监控配置")
    public Result<MonitorServiceConfig> create(@Valid @RequestBody ServiceConfigCreateRequest request) {
        return Result.ok(service.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "修改监控配置")
    public Result<MonitorServiceConfig> update(@PathVariable Long id,
                                               @Valid @RequestBody ServiceConfigUpdateRequest request) {
        return Result.ok(service.update(id, request));
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "启停监控配置")
    public Result<Void> updateStatus(@PathVariable Long id,
                                     @RequestParam Integer status) {
        service.updateStatus(id, status);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除监控配置")
    public Result<Void> delete(@PathVariable Long id) {
        if (!service.delete(id)) {
            return Result.error(SystemCodeEnum.NOT_FOUND);
        }
        return Result.ok();
    }
}
