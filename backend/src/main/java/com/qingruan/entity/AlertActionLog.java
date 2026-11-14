package com.qingruan.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("alert_action_log")
@Schema(description = "告警处置操作流水")
public class AlertActionLog {

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "关联告警事件ID")
    private Long alertId;

    @Schema(description = "操作类型：AUTO_HEAL / MANUAL_RECOVER / IGNORE / SYSTEM_RESOLVE")
    private String actionType;

    @Schema(description = "操作人ID")
    private Long operatorId;

    @Schema(description = "操作人姓名")
    private String operatorName;

    @Schema(description = "执行结果：SUCCESS / FAILED")
    private String actionResult;

    @Schema(description = "操作详情/自愈命令执行日志/备注说明")
    private String actionDetail;

    @Schema(description = "操作时间")
    private LocalDateTime createdAt;
}
