package com.qingruan.service;

import com.qingruan.dto.request.HeartbeatRequest;
import com.qingruan.dto.request.MetricsReportRequest;
import com.qingruan.dto.request.ServerRegisterRequest;
import com.qingruan.dto.response.MetricsQueryResponse;
import com.qingruan.dto.response.ServerRegisterResponse;

/**
 * Agent 接入服务
 * <p>
 * 注册 / 心跳 / 指标上报均基于 InfluxDB 时序库。
 */
public interface AgentService {

    /**
     * Agent 注册（重复注册则复用 serverCode / serverId，更新注册信息）
     */
    ServerRegisterResponse register(ServerRegisterRequest request);

    /**
     * Agent 心跳上报，写入心跳时序数据用于在线状态判断
     */
    void heartbeat(HeartbeatRequest request);

    /**
     * Agent 上报监控指标（InfluxDB 时序数据）
     */
    void saveMetrics(MetricsReportRequest request);

    /**
     * 从 InfluxDB 查询指定服务器的资源使用趋势数据
     */
    MetricsQueryResponse queryMetrics(String serverCode, String range);
}
