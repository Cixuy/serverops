package com.qingruan.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 邮件通知记录实体，对应 notification_log 表。
 */
@Data
@TableName("notification_log")
@Schema(description = "邮件通知记录")
public class NotificationLog {

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "关联的告警事件ID")
    private Long alertId;

    @Schema(description = "接收人邮箱列表（逗号分隔）")
    private String receiverEmails;

    @Schema(description = "发送状态：0-失败 1-成功")
    private Integer sendStatus;

    @Schema(description = "失败原因")
    private String errorMsg;

    @Schema(description = "发送时间")
    @TableField(insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private LocalDateTime createdAt;
}
