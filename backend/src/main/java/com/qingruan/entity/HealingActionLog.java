package com.qingruan.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("healing_action_log")
@Schema(description = "自愈操作日志")
public class HealingActionLog {

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "关联告警事件ID")
    private Long alertId;

    @Schema(description = "执行服务器编码")
    private String serverCode;

    @Schema(description = "监控配置ID")
    private Long configId;

    @Schema(description = "触发的自愈规则ID")
    private Long ruleId;

    @Schema(description = "服务名称（冗余）")
    private String serviceName;

    @Schema(description = "触发时的上下文数据快照(JSON)")
    private String triggerDetail;

    @Schema(description = "实际执行命令快照")
    private String executeCommand;

    @Schema(description = "命令标准输出/错误输出")
    private String commandOutput;

    @Schema(description = "执行状态：SUCCESS / FAILED / TIMEOUT / RETRYING")
    private String executionStatus;

    @Schema(description = "当前重试次数（从0开始）")
    private Integer retryCount;

    @Schema(description = "开始执行时间")
    private LocalDateTime startedAt;

    @Schema(description = "执行结束时间")
    private LocalDateTime finishedAt;

    @Schema(description = "执行耗时（毫秒）")
    private Integer durationMs;

    @Schema(description = "记录创建时间")
    private LocalDateTime createdAt;
}
