package com.qingruan.websocket.client.model;

import cn.hutool.core.annotation.Alias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 后端 → 客户端：自愈规则绑定变更通知（与客户端 WS 协议一致）。
 * <p>
 * 示例：
 * {"action":"HEALING_RULE_BINDING_CHANGE","request_id":"uuid","data":[{...}]}
 */
@Data
public class HealingRuleBindingChangeMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 固定动作标识 */
    private String action = "HEALING_RULE_BINDING_CHANGE";

    @Alias("request_id")
    @JsonProperty("request_id")
    private String requestId;

    /** 本次变更涉及的一个或多个绑定项 */
    private List<HealingRuleBindingChangeItem> data;
}
