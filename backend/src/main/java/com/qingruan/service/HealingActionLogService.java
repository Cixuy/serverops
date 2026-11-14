package com.qingruan.service;

import com.qingruan.common.result.PageResult;
import com.qingruan.dto.request.HealingActionLogCreateRequest;
import com.qingruan.vo.HealingActionLogCreateResponse;
import com.qingruan.vo.HealingActionLogInfo;

import java.time.LocalDateTime;

public interface HealingActionLogService {

    /**
     * 新增/补录一条自愈操作日志，落库 healing_action_log 表
     *
     * @param request 新增自愈操作日志请求
     * @return 落库后的主键ID
     */
    HealingActionLogCreateResponse createHealingActionLog(HealingActionLogCreateRequest request);

    /**
     * 分页查询自愈操作日志，支持按关联告警ID、服务器编码、监控配置ID、自愈规则ID、执行状态及执行时间范围筛选
     *
     * @param pageNum         页码
     * @param pageSize        每页数量
     * @param alertId         关联告警ID
     * @param serverCode      服务器编码
     * @param configId        监控配置ID
     * @param ruleId          自愈规则ID
     * @param executionStatus 执行状态：SUCCESS / FAILED / TIMEOUT / RETRYING
     * @param startTime       开始时间下限 (date-time)
     * @param endTime         开始时间上限 (date-time)
     * @return 分页结果
     */
    PageResult<HealingActionLogInfo> listHealingActionLogs(Integer pageNum, Integer pageSize,
                                                           Long alertId, String serverCode, Long configId, Long ruleId,
                                                           String executionStatus,
                                                           LocalDateTime startTime, LocalDateTime endTime);
}
