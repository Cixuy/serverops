package com.qingruan.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "自愈规则信息")
public class HealingRuleInfo {

    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "规则名称")
    private String ruleName;

    @Schema(description = "规则描述")
    private String description;

    @Schema(description = "组合触发条件表达式树(JSON)")
    private Object triggerConfig;

    @Schema(description = "自愈执行命令")
    private String executeCommand;

    @Schema(description = "执行超时时间(秒)")
    private Integer timeoutSeconds;

    @Schema(description = "最大重试次数")
    private Integer maxRetries;

    @Schema(description = "重试间隔(秒)")
    private Integer retryInterval;

    @Schema(description = "状态：0-停用 1-启用")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
