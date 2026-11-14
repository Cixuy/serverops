package com.qingruan.service;

/**
 * 邮件发送服务接口。
 * 提供告警邮件异步发送能力，发送结果写入 notification_log 表。
 */
public interface MailService {

    /**
     * 异步发送告警邮件并记录发送结果到 notification_log 表。
     *
     * @param alertId        关联的告警事件ID
     * @param receiverEmails 接收人邮箱（多个逗号分隔）
     * @param subject        邮件主题
     * @param content        邮件正文
     * @param html           是否 HTML 格式
     */
    void sendAlertNotification(Long alertId, String receiverEmails,
                               String subject, String content, boolean html);
}
