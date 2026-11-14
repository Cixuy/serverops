package com.qingruan.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 邮件自定义业务配置（对应 application.yml 中 mail-custom 前缀）。
 * SMTP 连接信息由 Spring Boot 自动绑定到 JavaMailSender，
 * 这里只管理业务层面的扩展配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mail-custom")
public class MailProperties {

    private String fromName;

    private int retryCount;

    private long retryInterval;
}
