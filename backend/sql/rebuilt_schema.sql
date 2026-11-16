-- 由《数据库表设计文档V1.2》生成
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `sys_user` (
  `id` BIGINT COMMENT '主键ID',
  `username` VARCHAR(50) NOT NULL COMMENT '登录账号',
  `password` VARCHAR(255) NOT NULL COMMENT '密码（BCrypt加密存储）',
  `nickname` VARCHAR(50) NOT NULL DEFAULT '' COMMENT '用户昵称',
  `role` VARCHAR(20) NOT NULL DEFAULT 'OPERATOR' COMMENT '角色：`ADMIN`(管理员) / `OPERATOR`(操作员)',
  `email` VARCHAR(100) COMMENT '邮箱（预留，通知用）',
  `phone` VARCHAR(20) COMMENT '手机号（预留，通知用）',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：`1`-启用 / `0`-禁用',
  `last_login_time` DATETIME COMMENT '最后登录时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间（自动更新）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `server_info` (
  `id` BIGINT COMMENT '主键ID',
  `server_code` VARCHAR(64) NOT NULL COMMENT '服务器唯一编码（用于判断服务器身份）',
  `hostname` VARCHAR(128) NOT NULL COMMENT '主机名',
  `ip_address` VARCHAR(45) NOT NULL COMMENT 'IP地址',
  `os_type` VARCHAR(20) NOT NULL DEFAULT 'WINDOWS' COMMENT '操作系统：`WINDOWS` / `LINUX`',
  `os_version` VARCHAR(100) COMMENT '系统详细版本号',
  `cpu_info` VARCHAR(255) COMMENT 'CPU信息，如: Intel Xeon E5-2680 v4 × 2 (28核)',
  `memory_info` VARCHAR(128) COMMENT '内存信息，如: 64 GB DDR4',
  `disk_info` VARCHAR(512) COMMENT '磁盘信息，如: 500GB SSD (C:) + 2TB HDD (D:)',
  `agent_version` VARCHAR(20) COMMENT 'Agent客户端版本号',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '在线状态：`1`-在线 / `0`-离线',
  `last_heartbeat` DATETIME COMMENT '最后一次心跳时间',
  `remark` VARCHAR(255) COMMENT '备注信息',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次注册时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '信息更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_server_code` (`server_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `monitor_service_config` (
  `id` BIGINT COMMENT '主键ID',
  `server_code` VARCHAR(64) NOT NULL COMMENT '关联的服务器唯一编码（关联 `server_info`）',
  `service_name` VARCHAR(100) NOT NULL COMMENT '服务名称（如：Nginx, MySQL, 业务后端），要与快照表一致修改',
  `process_name` VARCHAR(100) COMMENT '进程名称或关键字',
  `port` INT COMMENT '监听端口号',
  `check_interval` INT NOT NULL DEFAULT 60 COMMENT '探测频率（秒）',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '配置状态：1 - 启用 / 0 - 停用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `service_status_snapshot` (
  `id` BIGINT COMMENT '主键ID',
  `server_code` VARCHAR(64) NOT NULL COMMENT '服务器唯一编码',
  `config_id` BIGINT NOT NULL COMMENT '关联 `monitor_service_config.id`',
  `service_name` VARCHAR(100) NOT NULL COMMENT '冗余服务名称，方便查询展示',
  `is_alive` TINYINT NOT NULL DEFAULT 0 COMMENT '当前状态：1 - 正常存活 / 0 - 异常/离线',
  `pid` INT COMMENT '当前进程PID（若存活）',
  `port_status` TINYINT COMMENT '端口状态：1 - 监听中 / 0 - 未监听',
  `last_check_time` DATETIME NOT NULL COMMENT '客户端最后一次探测时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '服务端接收更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_config_id` (`config_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `healing_rule` (
  `id` BIGINT COMMENT '主键ID',
  `rule_name` VARCHAR(100) NOT NULL COMMENT '规则名称',
  `description` VARCHAR(500) COMMENT '规则描述',
  `trigger_config` JSON NOT NULL COMMENT '组合触发条件表达式树（结构见下方）',
  `execute_command` TEXT NOT NULL COMMENT '自愈执行命令',
  `timeout_seconds` INT NOT NULL DEFAULT 60 COMMENT '命令执行超时时间（秒）',
  `max_retries` INT NOT NULL DEFAULT 3 COMMENT '最大重试次数',
  `retry_interval` INT NOT NULL DEFAULT 30 COMMENT '重试间隔（秒）',
  `created_by` BIGINT COMMENT '创建人ID',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1-启用 / 0-停用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rule_name` (`rule_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `service_healing_rule_binding` (
  `id` BIGINT COMMENT '主键ID',
  `server_code` VARCHAR(64) NOT NULL COMMENT '服务器编码',
  `config_id` BIGINT NOT NULL COMMENT '监控服务配置ID',
  `rule_id` BIGINT NOT NULL COMMENT '自愈规则ID',
  `priority` INT NOT NULL DEFAULT 0 COMMENT '优先级（数值越小越优先）',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1-启用监听 / 0-停用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '绑定时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `healing_action_log` (
  `id` BIGINT COMMENT '主键ID',
  `alert_id` BIGINT COMMENT '关联告警事件ID（可为空）',
  `server_code` VARCHAR(64) NOT NULL COMMENT '执行服务器编码',
  `config_id` BIGINT NOT NULL COMMENT '监控配置ID',
  `rule_id` BIGINT NOT NULL COMMENT '触发的自愈规则ID',
  `service_name` VARCHAR(100) NOT NULL COMMENT '服务名称（冗余）',
  `trigger_detail` JSON NOT NULL COMMENT '触发时的上下文数据快照',
  `execute_command` TEXT NOT NULL COMMENT '实际执行命令快照',
  `command_output` TEXT COMMENT '命令标准输出/错误输出',
  `execution_status` VARCHAR(20) NOT NULL COMMENT 'SUCCESS / FAILED / TIMEOUT / RETRYING',
  `retry_count` INT NOT NULL DEFAULT 0 COMMENT '当前重试次数（从0开始）',
  `started_at` DATETIME NOT NULL COMMENT '开始执行时间',
  `finished_at` DATETIME COMMENT '执行结束时间',
  `duration_ms` INT COMMENT '执行耗时（毫秒）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `alert_event` (
  `id` BIGINT COMMENT '主键ID',
  `alert_no` VARCHAR(64) NOT NULL COMMENT '告警编号（业务生成，如 ALM202310240001）',
  `server_code` VARCHAR(64) NOT NULL COMMENT '发生告警的服务器编码',
  `config_id` BIGINT NOT NULL COMMENT '关联的监控配置ID',
  `service_name` VARCHAR(100) NOT NULL COMMENT '告警服务名称',
  `alert_type` VARCHAR(20) NOT NULL COMMENT '告警类型：`SERVICE_DOWN` (服务完全不可用) / `SERVICE_DEGRADED` (服务降级/部分异',
  `alert_level` VARCHAR(20) NOT NULL DEFAULT 'CRITICAL' COMMENT '告警级别：`INFO` / `WARNING` / `CRITICAL`',
  `alert_content` JSON NOT NULL COMMENT '结构化异常上下文',
  `alert_status` VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '告警状态：`PENDING`(待处理) / `HEALING`(自愈中) / `RESOLVED`(已恢复) / `IG',
  `healing_rule_id` BIGINT COMMENT '触发自愈的规则ID',
  `auto_heal_status` VARCHAR(20) COMMENT '自愈结果：SUCCESS / FAILED / SKIPPED',
  `trigger_time` DATETIME NOT NULL COMMENT '客户端触发告警的时间',
  `resolve_time` DATETIME COMMENT '告警恢复/处理完成时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_alert_no` (`alert_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `alert_action_log` (
  `id` BIGINT COMMENT '主键ID',
  `alert_id` BIGINT NOT NULL COMMENT '关联 `alert_event.id`',
  `action_type` VARCHAR(20) NOT NULL COMMENT '操作类型：`AUTO_HEAL`(自愈) / `MANUAL_RECOVER`(手动恢复) / `IGNORE`(搁置)',
  `operator_id` BIGINT COMMENT '操作人ID（关联 `sys_user.id`，系统自动触发则为空）',
  `operator_name` VARCHAR(50) COMMENT '操作人姓名（冗余，防止用户被删后记录丢失）',
  `action_result` VARCHAR(20) NOT NULL DEFAULT 'SUCCESS' COMMENT '执行结果：`SUCCESS` / `FAILED`',
  `action_detail` TEXT COMMENT '操作详情',
  `healing_rule_id` BIGINT COMMENT '关联自愈规则ID（AUTO_HEAL类型时填写）',
  `healing_log_id` BIGINT COMMENT '关联自愈操作日志ID',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `notification_log` (
  `id` BIGINT COMMENT '主键ID',
  `alert_id` BIGINT NOT NULL COMMENT '关联的告警事件ID',
  `receiver_emails` TEXT NOT NULL COMMENT '接收人邮箱列表（逗号分隔）',
  `send_status` TINYINT NOT NULL DEFAULT 0 COMMENT '发送状态：0 - 失败 / 1 - 成功',
  `error_msg` VARCHAR(500) COMMENT '失败原因（如SMTP报错信息）',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `ai_analysis_result` (
  `id` BIGINT COMMENT '主键ID',
  `task_id` BIGINT NOT NULL COMMENT '关联 ai_analysis_task.id',
  `alert_id` BIGINT NOT NULL COMMENT '关联 alert_event.id',
  `root_cause_summary` TEXT COMMENT 'AI总结的根因描述',
  `severity_assessment` VARCHAR(20) COMMENT 'AI评估的严重程度：LOW / MEDIUM / HIGH / CRITICAL',
  `confidence_score` DECIMAL(5,2) COMMENT 'AI分析置信度（0.00 ~ 100.00）',
  `recommendations` JSON NOT NULL COMMENT 'AI处置建议列表（结构化，含优先级、操作步骤、预期效果）',
  `reasoning_chain` TEXT COMMENT 'AI推理链/思维过程（可选，用于可解释性展示）',
  `user_feedback` VARCHAR(20) COMMENT '用户对AI分析的反馈：ACCURATE / INACCURATE / PARTIAL',
  `feedback_comment` TEXT COMMENT '用户反馈备注',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
