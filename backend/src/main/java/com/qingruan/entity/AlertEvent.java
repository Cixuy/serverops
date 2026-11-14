package com.qingruan.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("alert_event")
@Schema(description = "告警事件")
public class AlertEvent {

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "告警编号")
    private String alertNo;

    @Schema(description = "服务器唯一编码")
    private String serverCode;

    @Schema(description = "关联的监控配置ID")
    private Long configId;

    @Schema(description = "关联的自愈规则ID(对应表字段 healing_rule_id)")
    @TableField("healing_rule_id")
    private Long ruleId;

    @Schema(description = "告警服务名称")
    private String serviceName;

    @Schema(description = "告警类型：SERVICE_DOWN / SERVICE_DEGRADED / CONFIG_ERROR")
    private String alertType;

    @Schema(description = "告警级别：INFO / WARNING / CRITICAL")
    private String alertLevel;

    @Schema(description = "结构化异常上下文(JSON)")
    private String alertContent;

    @Schema(description = "告警状态：PENDING / HEALING / RESOLVED / IGNORED")
    private String alertStatus;

    @Schema(description = "客户端触发告警的时间")
    private LocalDateTime triggerTime;

    @Schema(description = "告警恢复/处理完成时间")
    private LocalDateTime resolveTime;

    @Schema(description = "自愈结果：SUCCESS / FAILED / SKIPPED(对应表字段 auto_heal_status)")
    @TableField("auto_heal_status")
    private String autoHealStatus;

    @Schema(description = "记录创建时间")
    private LocalDateTime createdAt;
}
