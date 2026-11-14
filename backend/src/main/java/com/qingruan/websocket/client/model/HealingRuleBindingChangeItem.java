package com.qingruan.websocket.client.model;

import cn.hutool.core.annotation.Alias;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 自愈规则绑定变更通知中的单条绑定项（蛇形命名字段，与通信协议一致）。
 */
@Data
@Schema(description = "自愈规则绑定变更项")
public class HealingRuleBindingChangeItem implements Serializable {

    private static final long serialVersionUID = 1L;

    @Alias("binding_id")
    @JsonProperty("binding_id")
    private Long bindingId;

    @Alias("server_code")
    @JsonProperty("server_code")
    private String serverCode;

    @Alias("config_id")
    @JsonProperty("config_id")
    private Long configId;

    @Alias("rule_id")
    @JsonProperty("rule_id")
    private Long ruleId;

    private Integer priority;

    private Integer status;

    @Alias("rule_snapshot")
    @JsonProperty("rule_snapshot")
    private RuleSnapshot ruleSnapshot;

    @Alias("action_type")
    @JsonProperty("action_type")
    private String actionType;
}
