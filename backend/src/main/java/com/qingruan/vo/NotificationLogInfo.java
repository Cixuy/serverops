package com.qingruan.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "邮件通知记录信息")
public class NotificationLogInfo {

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
    private LocalDateTime createdAt;
}
