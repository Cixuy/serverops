package com.qingruan.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "自愈操作日志信息")
public class HealingActionLogInfo {

    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "关联告警ID")
    private Long alertId;

    @Schema(description = "执行服务器编码")
    private String serverCode;

    @Schema(description = "监控配置ID")
    private Long configId;

    @Schema(description = "服务名称")
    private String serviceName;

    @Schema(description = "触发的自愈规则ID")
    private Long ruleId;

    @Schema(description = "自愈规则名称（关联 healing_rule.rule_name）")
    private String ruleName;

    @Schema(description = "触发时的上下文数据快照")
    private Object triggerDetail;

    @Schema(description = "实际执行命令")
    private String executeCommand;

    @Schema(description = "命令执行输出")
    private String commandOutput;

    @Schema(description = "执行状态：SUCCESS / FAILED / TIMEOUT / RETRYING")
    private String executionStatus;

    @Schema(description = "当前重试次数")
    private Integer retryCount;

    @Schema(description = "开始执行时间")
    private LocalDateTime startedAt;

    @Schema(description = "执行结束时间")
    private LocalDateTime finishedAt;

    @Schema(description = "执行耗时(毫秒)")
    private Integer durationMs;
}
