package com.qingruan.service.impl;

import com.qingruan.common.config.MailProperties;
import com.qingruan.entity.NotificationLog;
import com.qingruan.mapper.NotificationLogMapper;
import com.qingruan.service.MailService;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 邮件发送服务实现。
 * <p>
 * 核心流程：
 * 1. 告警触发时调用 sendAlertNotification，异步发送邮件
 * 2. 发送失败自动重试（最多3次）
 * 3. 发送结果（成功/失败）写入 notification_log 表
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailServiceImpl implements MailService {

    private final JavaMailSender mailSender;
    private final MailProperties mailProperties;
    private final NotificationLogMapper notificationLogMapper;

    /** SMTP 认证账号（即发件人邮箱地址），来自 spring.mail.username */
    @Value("${spring.mail.username}")
    private String senderEmail;

    @Async("mailExecutor")
    @Override
    public void sendAlertNotification(Long alertId, String receiverEmails,
                                      String subject, String content, boolean html) {
        NotificationLog logRecord = new NotificationLog();
        logRecord.setAlertId(alertId);
        logRecord.setReceiverEmails(receiverEmails);

        int maxRetry = Math.max(1, mailProperties.getRetryCount());
        Exception lastError = null;
        for (int attempt = 1; attempt <= maxRetry; attempt++) {
            try {
                doSend(receiverEmails, subject, content, html);
                logRecord.setSendStatus(1);
                log.info("告警邮件发送成功 -> alertId={}, to={}, attempt={}", alertId, receiverEmails, attempt);
                notificationLogMapper.insert(logRecord);
                return;
            } catch (Exception e) {
                lastError = e;
                log.warn("告警邮件发送失败(第{}次) -> alertId={}, error={}", attempt, alertId, e.getMessage());
                if (attempt < maxRetry) {
                    sleep(mailProperties.getRetryInterval());
                }
            }
        }

        logRecord.setSendStatus(0);
        logRecord.setErrorMsg(truncate(lastError.getMessage(), 500));
        log.error("告警邮件最终发送失败 -> alertId={}, to={}", alertId, receiverEmails);
        notificationLogMapper.insert(logRecord);
    }

    /**
     * 执行实际邮件发送。
     */
    private void doSend(String to, String subject, String content, boolean html) throws Exception {
        MimeMessage mimeMessage = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
        String fromName = mailProperties.getFromName();
        helper.setFrom(senderEmail, fromName == null ? senderEmail : fromName);
        helper.setTo(to.split(","));
        helper.setSubject(subject);
        helper.setText(content, html);
        mailSender.send(mimeMessage);
    }

    private String truncate(String msg, int maxLen) {
        if (msg == null) {
            return null;
        }
        return msg.length() > maxLen ? msg.substring(0, maxLen) : msg;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
