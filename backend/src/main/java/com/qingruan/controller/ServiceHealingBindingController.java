package com.qingruan.controller;

import com.qingruan.common.constant.SystemCodeEnum;
import com.qingruan.common.result.PageResult;
import com.qingruan.common.result.Result;
import com.qingruan.service.ServiceHealingBindingService;
import com.qingruan.vo.BoundHealingRuleInfo;
import com.qingruan.vo.ServiceHealingBindingCreateRequest;
import com.qingruan.vo.ServiceHealingBindingInfo;
import com.qingruan.vo.ServiceHealingBindingStatusRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
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
@RequestMapping("/service-healing-bindings")
@RequiredArgsConstructor
@Tag(name = "12-服务规则绑定", description = "服务规则绑定")
public class ServiceHealingBindingController {

    private final ServiceHealingBindingService serviceHealingBindingService;

    @Operation(summary = "为某服务绑定自愈规则", description = "将一条自愈规则绑定到指定服务（默认启用监听）")
    @PostMapping
    public Result<ServiceHealingBindingInfo> createBinding(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "服务-规则绑定请求")
            @RequestBody ServiceHealingBindingCreateRequest request) {
        if (!StringUtils.hasText(request.getServerCode())
                || request.getConfigId() == null
                || request.getRuleId() == null) {
            return Result.error(SystemCodeEnum.BAD_REQUEST);
        }
        return Result.ok(serviceHealingBindingService.createBinding(request));
    }

    @Operation(summary = "查询某服务已绑定的规则列表", description = "按服务器编码与监控配置ID分页查询已绑定的自愈规则（携带 rule_id/bind_id，按绑定优先级降序）")
    @GetMapping
    public Result<PageResult<BoundHealingRuleInfo>> listBoundRules(
            @Parameter(description = "服务器编码", example = "SRV-5SLCKN5A")
            @RequestParam String serverCode,
            @Parameter(description = "监控配置ID", example = "15")
            @RequestParam Long configId,
            @Parameter(description = "页码，从1开始，默认1")
            @RequestParam(required = false) Integer pageNum,
            @Parameter(description = "每页数量，默认100")
            @RequestParam(required = false) Integer pageSize) {
        if (!StringUtils.hasText(serverCode) || configId == null) {
            return Result.error(SystemCodeEnum.BAD_REQUEST);
        }
        return Result.ok(serviceHealingBindingService.listBoundRules(serverCode, configId, pageNum, pageSize));
    }

    @Operation(summary = "解除服务与规则的绑定", description = "按绑定记录ID解除服务与自愈规则的绑定")
    @DeleteMapping("/{id}")
    public Result<Void> deleteBinding(
            @Parameter(description = "绑定记录ID", example = "1")
            @PathVariable("id") Long id) {
        serviceHealingBindingService.deleteBinding(id);
        return Result.ok();
    }

    @Operation(summary = "启停某绑定的监听状态", description = "按绑定记录ID启用或停用其监听状态")
    @PutMapping("/{id}/status")
    public Result<Void> toggleBindingStatus(
            @Parameter(description = "绑定记录ID", example = "1")
            @PathVariable("id") Long id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "目标状态")
            @RequestBody ServiceHealingBindingStatusRequest request) {
        Integer status = request.getStatus();
        if (status == null || (status != 0 && status != 1)) {
            return Result.error(SystemCodeEnum.BAD_REQUEST);
        }
        serviceHealingBindingService.toggleBindingStatus(id, status);
        return Result.ok();
    }
}
