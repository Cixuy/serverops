package com.qingruan.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "新增自愈规则请求")
public class HealingRuleCreateRequest {

    @Schema(description = "规则名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String ruleName;

    @Schema(description = "规则描述")
    private String description;

    @Schema(description = "组合触发条件表达式树(JSON)", requiredMode = Schema.RequiredMode.REQUIRED,
            implementation = TriggerConfig.class)
    private Object triggerConfig;

    @Schema(description = "自愈执行命令", requiredMode = Schema.RequiredMode.REQUIRED)
    private String executeCommand;

    @Schema(description = "执行超时时间(秒)，默认60")
    private Integer timeoutSeconds;

    @Schema(description = "最大重试次数，默认3")
    private Integer maxRetries;

    @Schema(description = "重试间隔(秒)，默认30")
    private Integer retryInterval;
}
