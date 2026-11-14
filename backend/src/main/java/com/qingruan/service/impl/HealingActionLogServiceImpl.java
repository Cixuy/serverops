package com.qingruan.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qingruan.common.result.PageResult;
import com.qingruan.dto.request.HealingActionLogCreateRequest;
import com.qingruan.entity.HealingActionLog;
import com.qingruan.entity.HealingRule;
import com.qingruan.mapper.HealingActionLogMapper;
import com.qingruan.mapper.HealingRuleMapper;
import com.qingruan.service.HealingActionLogService;
import com.qingruan.vo.HealingActionLogCreateResponse;
import com.qingruan.vo.HealingActionLogInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class HealingActionLogServiceImpl extends ServiceImpl<HealingActionLogMapper, HealingActionLog> implements HealingActionLogService {

    private final HealingRuleMapper healingRuleMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HealingActionLogCreateResponse createHealingActionLog(HealingActionLogCreateRequest request) {
        HealingActionLog log = new HealingActionLog();
        log.setAlertId(request.getAlertId());
        log.setServerCode(request.getServerCode());
        log.setConfigId(request.getConfigId());
        log.setRuleId(request.getRuleId());
        log.setServiceName(request.getServiceName());
        // trigger_detail 为 MySQL JSON 列且 NOT NULL，实体以字符串存储；
        // 快照对象序列化为字符串，未传时落库空对象 {} 以满足非空约束
        log.setTriggerDetail(request.getTriggerDetail() != null
                ? JSON.toJSONString(request.getTriggerDetail()) : "{}");
        log.setExecuteCommand(request.getExecuteCommand());
        log.setCommandOutput(request.getCommandOutput());
        log.setExecutionStatus(request.getExecutionStatus());
        // 重试次数默认从 0 开始
        log.setRetryCount(request.getRetryCount() != null ? request.getRetryCount() : 0);
        // 带时区偏移的时间统一转为系统本地时区的 LocalDateTime
        if (request.getStartedAt() != null) {
            log.setStartedAt(request.getStartedAt().atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime());
        }
        if (request.getFinishedAt() != null) {
            log.setFinishedAt(request.getFinishedAt().atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime());
        }
        log.setDurationMs(request.getDurationMs());
        log.setCreatedAt(LocalDateTime.now());

        save(log);

        HealingActionLogCreateResponse response = new HealingActionLogCreateResponse();
        response.setId(log.getId());
        return response;
    }

    @Override
    public PageResult<HealingActionLogInfo> listHealingActionLogs(Integer pageNum, Integer pageSize,
                                                                  Long alertId, String serverCode, Long configId, Long ruleId,
                                                                  String executionStatus,
                                                                  LocalDateTime startTime, LocalDateTime endTime) {
        Page<HealingActionLog> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<HealingActionLog> wrapper = new LambdaQueryWrapper<>();

        if (alertId != null) {
            wrapper.eq(HealingActionLog::getAlertId, alertId);
        }
        if (StringUtils.hasText(serverCode)) {
            wrapper.eq(HealingActionLog::getServerCode, serverCode);
        }
        if (configId != null) {
            wrapper.eq(HealingActionLog::getConfigId, configId);
        }
        if (ruleId != null) {
            wrapper.eq(HealingActionLog::getRuleId, ruleId);
        }
        if (StringUtils.hasText(executionStatus)) {
            wrapper.eq(HealingActionLog::getExecutionStatus, executionStatus);
        }
        // 时间筛选作用于开始执行时间(started_at)
        if (startTime != null && endTime != null) {
            wrapper.between(HealingActionLog::getStartedAt, startTime, endTime);
        } else if (startTime != null) {
            wrapper.ge(HealingActionLog::getStartedAt, startTime);
        } else if (endTime != null) {
            wrapper.le(HealingActionLog::getStartedAt, endTime);
        }
        wrapper.orderByDesc(HealingActionLog::getStartedAt);

        IPage<HealingActionLog> result = page(page, wrapper);

        // 批量查询关联的自愈规则名称，避免逐条查询造成 N+1
        Map<Long, HealingRule> ruleMap = loadRuleMap(result.getRecords());
        List<HealingActionLogInfo> infos = result.getRecords().stream()
                .map(logRecord -> toInfo(logRecord, ruleMap))
                .collect(Collectors.toList());
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), infos);
    }

    /**
     * 批量查询当前页日志涉及的规则，构建 ruleId -> HealingRule 映射
     */
    private Map<Long, HealingRule> loadRuleMap(List<HealingActionLog> logs) {
        List<Long> ruleIds = logs.stream()
                .map(HealingActionLog::getRuleId)
                .filter(ruleId -> ruleId != null)
                .distinct()
                .collect(Collectors.toList());
        if (ruleIds.isEmpty()) {
            return Map.of();
        }
        return healingRuleMapper.selectBatchIds(ruleIds).stream()
                .collect(Collectors.toMap(HealingRule::getId, Function.identity()));
    }

    private HealingActionLogInfo toInfo(HealingActionLog logRecord, Map<Long, HealingRule> ruleMap) {
        HealingActionLogInfo info = new HealingActionLogInfo();
        info.setId(logRecord.getId());
        info.setAlertId(logRecord.getAlertId());
        info.setServerCode(logRecord.getServerCode());
        info.setConfigId(logRecord.getConfigId());
        info.setServiceName(logRecord.getServiceName());
        info.setRuleId(logRecord.getRuleId());
        // 从规则表中补齐规则名称，规则不存在时返回 null
        HealingRule rule = logRecord.getRuleId() != null ? ruleMap.get(logRecord.getRuleId()) : null;
        info.setRuleName(rule != null ? rule.getRuleName() : null);
        // trigger_detail 为 MySQL JSON 列，实体中以字符串存储，此处解析为对象输出
        info.setTriggerDetail(logRecord.getTriggerDetail() != null
                ? JSON.parse(logRecord.getTriggerDetail()) : null);
        info.setExecuteCommand(logRecord.getExecuteCommand());
        info.setCommandOutput(logRecord.getCommandOutput());
        info.setExecutionStatus(logRecord.getExecutionStatus());
        info.setRetryCount(logRecord.getRetryCount());
        info.setStartedAt(logRecord.getStartedAt());
        info.setFinishedAt(logRecord.getFinishedAt());
        info.setDurationMs(logRecord.getDurationMs());
        return info;
    }
}
