package com.qingruan.controller;

import com.qingruan.common.constant.SystemCodeEnum;
import com.qingruan.common.result.PageResult;
import com.qingruan.common.result.Result;
import com.qingruan.service.HealingRuleService;
import com.qingruan.vo.HealingRuleCreateRequest;
import com.qingruan.vo.HealingRuleInfo;
import com.qingruan.vo.HealingRuleStatusRequest;
import com.qingruan.vo.HealingRuleUpdateRequest;
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
@RequestMapping("/healing-rules")
@RequiredArgsConstructor
@Tag(name = "11-自愈规则管理", description = "自愈规则管理")
public class HealingRuleController {

    private final HealingRuleService healingRuleService;

    @Operation(summary = "新增自愈规则", description = "创建一条自愈规则，默认启用")
    @PostMapping
    public Result<HealingRuleInfo> createHealingRule(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "自愈规则创建请求")
            @RequestBody HealingRuleCreateRequest request) {
        if (!StringUtils.hasText(request.getRuleName())
                || request.getTriggerConfig() == null
                || !StringUtils.hasText(request.getExecuteCommand())) {
            return Result.error(SystemCodeEnum.BAD_REQUEST);
        }
        HealingRuleInfo info = healingRuleService.createHealingRule(request);
        if (info == null) {
            return Result.error(SystemCodeEnum.BAD_REQUEST.getCode(), "规则名称已存在");
        }
        return Result.ok(info);
    }

    @Operation(summary = "分页查询自愈规则列表", description = "分页查询自愈规则，支持按名称模糊搜索与状态筛选")
    @GetMapping
    public Result<PageResult<HealingRuleInfo>> listHealingRules(
            @Parameter(description = "页码", example = "1")
            @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页数量", example = "20")
            @RequestParam(defaultValue = "20") Integer pageSize,
            @Parameter(description = "模糊搜索规则名称")
            @RequestParam(required = false) String ruleName,
            @Parameter(description = "状态: 0-停用 1-启用")
            @RequestParam(required = false) Integer status) {
        return Result.ok(healingRuleService.listHealingRules(pageNum, pageSize, ruleName, status));
    }

    @Operation(summary = "修改自愈规则", description = "部分更新自愈规则，仅更新请求中传入的字段")
    @PutMapping("/{id}")
    public Result<HealingRuleInfo> updateHealingRule(
            @Parameter(description = "规则ID", example = "1")
            @PathVariable("id") Long id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "自愈规则修改请求")
            @RequestBody HealingRuleUpdateRequest request) {
        return Result.ok(healingRuleService.updateHealingRule(id, request));
    }

    @Operation(summary = "删除自愈规则", description = "物理删除指定自愈规则")
    @DeleteMapping("/{id}")
    public Result<Void> deleteHealingRule(
            @Parameter(description = "规则ID", example = "1")
            @PathVariable("id") Long id) {
        healingRuleService.deleteHealingRule(id);
        return Result.ok();
    }

    @Operation(summary = "启停自愈规则", description = "启用或停用指定自愈规则")
    @PutMapping("/{id}/status")
    public Result<Void> toggleHealingRuleStatus(
            @Parameter(description = "规则ID", example = "1")
            @PathVariable("id") Long id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "目标状态")
            @RequestBody HealingRuleStatusRequest request) {
        Integer status = request.getStatus();
        if (status == null || (status != 0 && status != 1)) {
            return Result.error(SystemCodeEnum.BAD_REQUEST);
        }
        healingRuleService.toggleHealingRuleStatus(id, status);
        return Result.ok();
    }
}
