package com.qingruan.agent.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 分析入参：从 alert_event 抽取出的告警上下文，交由 AnalysisProvider 分析。
 */
@Data
public class AnalysisInput implements Serializable {

    /** 关联告警事件ID */
    private Long alertId;

    /** 告警类型：SERVICE_DOWN / SERVICE_DEGRADED / CONFIG_ERROR */
    private String alertType;

    /** 告警级别：INFO / WARNING / CRITICAL */
    private String alertLevel;

    /** 告警服务名称 */
    private String serviceName;

    /** 服务器唯一编码 */
    private String serverCode;

    /** 结构化异常上下文（原始对象，序列化为 JSON 传给模型） */
    private Object alertContent;
}
