package com.qingruan.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 某服务已绑定的自愈规则信息（在 HealingRuleInfo 基础上携带绑定维度字段）。
 */
@Data
@Schema(description = "服务已绑定的自愈规则信息")
public class BoundHealingRuleInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "自愈规则ID")
    @JsonProperty("rule_id")
    private Long ruleId;

    @Schema(description = "绑定记录ID")
    @JsonProperty("bind_id")
    private Long bindId;

    @Schema(description = "规则名称")
    private String ruleName;

    @Schema(description = "规则描述")
    private String description;

    @Schema(description = "组合触发条件表达式树(JSON 对象)")
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
