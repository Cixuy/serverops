package com.qingruan.service.impl;

import com.qingruan.common.config.MailProperties;
import com.qingruan.entity.NotificationLog;
import com.qingruan.mapper.NotificationLogMapper;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

/**
 * 邮件发送服务测试：覆盖发送成功落库、发送失败重试与审计落库两条路径。
 * 注：@Async 注解在直接 new 调用时不生效，测试方法同步执行，便于断言。
 */
class MailServiceImplTest {

    private JavaMailSender mailSender;
    private MailProperties mailProperties;
    private NotificationLogMapper logMapper;
    private MailServiceImpl mailService;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        mailProperties = new MailProperties();
        mailProperties.setFromName("ServerOps");
        mailProperties.setRetryCount(3);
        mailProperties.setRetryInterval(1); // 测试时缩短重试间隔
        logMapper = mock(NotificationLogMapper.class);

        mailService = new MailServiceImpl(mailSender, mailProperties, logMapper);
        // 补偿 @Value 注入的 senderEmail（无 Spring 上下文时为 null）
        ReflectionTestUtils.setField(mailService, "senderEmail", "noreply@serverops.com");
    }

    @Test
    void sendAlertNotificationSuccessWritesSuccessLog() throws Exception {
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        mailService.sendAlertNotification(
                100L, "a@x.com,b@x.com", "告警主题", "<div>body</div>", true);

        // 发送一次，成功日志写入一次
        verify(mailSender, times(1)).send(any(MimeMessage.class));
        verify(logMapper, times(1)).insert(any(NotificationLog.class));

        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logMapper).insert(captor.capture());
        assertEquals(1, captor.getValue().getSendStatus(), "应记录为发送成功");
        assertEquals(100L, captor.getValue().getAlertId());
    }

    @Test
    void sendAlertNotificationFailureRetriesThenWritesFailureLog() throws Exception {
        MimeMessage mimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        // 每次发送都抛异常，触发重试
        doThrow(new RuntimeException("smtp down")).when(mailSender).send(any(MimeMessage.class));

        mailService.sendAlertNotification(
                200L, "a@x.com", "告警主题", "<div>body</div>", true);

        // retryCount=3，应尝试 3 次
        verify(mailSender, times(3)).send(any(MimeMessage.class));
        verify(logMapper, times(1)).insert(any(NotificationLog.class));

        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logMapper).insert(captor.capture());
        assertEquals(0, captor.getValue().getSendStatus(), "最终失败应记录为发送失败");
        assertEquals(200L, captor.getValue().getAlertId());
    }
}
