package com.qingruan.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "修改自愈规则请求（字段均为可选，仅更新传入的字段）")
public class HealingRuleUpdateRequest {

    @Schema(description = "规则名称")
    private String ruleName;

    @Schema(description = "规则描述")
    private String description;

    @Schema(description = "组合触发条件表达式树(JSON)", implementation = TriggerConfig.class)
    private Object triggerConfig;

    @Schema(description = "自愈执行命令")
    private String executeCommand;

    @Schema(description = "执行超时时间(秒)")
    private Integer timeoutSeconds;

    @Schema(description = "最大重试次数")
    private Integer maxRetries;

    @Schema(description = "重试间隔(秒)")
    private Integer retryInterval;
}
