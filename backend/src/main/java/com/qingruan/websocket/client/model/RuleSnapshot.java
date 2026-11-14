package com.qingruan.websocket.client.model;

import cn.hutool.core.annotation.Alias;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 随绑定变更下发的自愈规则快照（蛇形命名字段，与通信协议一致）。
 */
@Data
@Schema(description = "自愈规则快照")
public class RuleSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    @Alias("rule_name")
    @JsonProperty("rule_name")
    private String ruleName;

    /** 组合触发条件表达式树（JSON 对象） */
    @Alias("trigger_config")
    @JsonProperty("trigger_config")
    private Object triggerConfig;

    /** 自愈执行命令 */
    @Alias("execute_command")
    @JsonProperty("execute_command")
    private String executeCommand;

    @Alias("timeout_seconds")
    @JsonProperty("timeout_seconds")
    private Integer timeoutSeconds;

    @Alias("max_retries")
    @JsonProperty("max_retries")
    private Integer maxRetries;

    @Alias("retry_interval")
    @JsonProperty("retry_interval")
    private Integer retryInterval;
}
