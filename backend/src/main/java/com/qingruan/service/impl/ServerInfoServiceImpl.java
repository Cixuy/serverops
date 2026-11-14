package com.qingruan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.influxdb.client.DeleteApi;
import com.influxdb.client.InfluxDBClient;
import com.qingruan.common.config.InfluxDBConfig;
import com.qingruan.common.result.PageResult;
import com.qingruan.entity.AlertActionLog;
import com.qingruan.entity.AlertEvent;
import com.qingruan.entity.HealingActionLog;
import com.qingruan.entity.MonitorServiceConfig;
import com.qingruan.entity.NotificationLog;
import com.qingruan.entity.ServerInfo;
import com.qingruan.entity.ServiceHealingBinding;
import com.qingruan.entity.ServiceStatusSnapshot;
import com.qingruan.mapper.AlertActionLogMapper;
import com.qingruan.mapper.AlertEventMapper;
import com.qingruan.mapper.HealingActionLogMapper;
import com.qingruan.mapper.NotificationLogMapper;
import com.qingruan.mapper.ServerInfoMapper;
import com.qingruan.mapper.ServiceHealingBindingMapper;
import com.qingruan.service.MonitorServiceConfigService;
import com.qingruan.service.ServerInfoService;
import com.qingruan.service.ServiceStatusSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ServerInfoServiceImpl extends ServiceImpl<ServerInfoMapper, ServerInfo> implements ServerInfoService {

    private final InfluxDBClient influxDBClient;
    private final InfluxDBConfig influxDBConfig;

    // 级联清理所需的其他业务 Service（IService 实现）与 Mapper
    private final MonitorServiceConfigService monitorServiceConfigService;
    private final ServiceStatusSnapshotService serviceStatusSnapshotService;
    private final ServiceHealingBindingMapper serviceHealingBindingMapper;
    private final HealingActionLogMapper healingActionLogMapper;
    private final AlertEventMapper alertEventMapper;
    private final AlertActionLogMapper alertActionLogMapper;
    private final NotificationLogMapper notificationLogMapper;

    @Override
    public PageResult<ServerInfo> getServerList(Integer pageNum, Integer pageSize, Integer status, String keyword) {
        Page<ServerInfo> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<ServerInfo> wrapper = new LambdaQueryWrapper<>();

        if (status != null) {
            wrapper.eq(ServerInfo::getStatus, status);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(ServerInfo::getHostname, keyword)
                    .or().like(ServerInfo::getIpAddress, keyword));
        }

        wrapper.orderByDesc(ServerInfo::getCreatedAt);

        IPage<ServerInfo> result = page(page, wrapper);
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords());
    }

    @Override
    public ServerInfo getServerDetail(Long id) {
        return getById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteServer(Long id) {
        ServerInfo serverInfo = getById(id);
        if (serverInfo == null) {
            return false;
        }
        String serverCode = serverInfo.getServerCode();

        // 1) 级联清理所有关联该服务器的业务数据（按 server_code 维度）
        cleanupRelatedData(serverCode);

        // 2) 清理关联的 InfluxDB 时序数据（server_metrics / server_disk_metrics 均按 server_code 打标）
        cleanupInfluxDB(serverCode);

        // 3) 最后物理删除 MySQL 的服务器主记录（schema 无 is_deleted 字段）
        return removeById(id);
    }

    /**
     * 级联删除所有与服务器关联的业务表数据，确保删除服务器时不残留孤儿数据。
     * 关联维度统一使用 server_code。
     */
    private void cleanupRelatedData(String serverCode) {
        // 监控服务配置
        monitorServiceConfigService.remove(
                new LambdaQueryWrapper<MonitorServiceConfig>().eq(MonitorServiceConfig::getServerCode, serverCode));

        // 服务状态快照
        serviceStatusSnapshotService.remove(
                new LambdaQueryWrapper<ServiceStatusSnapshot>().eq(ServiceStatusSnapshot::getServerCode, serverCode));

        // 服务-自愈规则绑定（自定义 Service，无 remove，使用 Mapper 删除）
        serviceHealingBindingMapper.delete(
                new LambdaQueryWrapper<ServiceHealingBinding>().eq(ServiceHealingBinding::getServerCode, serverCode));

        // 自愈操作日志（自定义 Service，无 remove，使用 Mapper 删除）
        healingActionLogMapper.delete(
                new LambdaQueryWrapper<HealingActionLog>().eq(HealingActionLog::getServerCode, serverCode));

        // 告警事件：先取该服务器下所有告警ID，级联删除其子表(处置流水/通知记录)后再删主表
        List<Long> alertIds = alertEventMapper.selectList(
                        new LambdaQueryWrapper<AlertEvent>()
                                .eq(AlertEvent::getServerCode, serverCode)
                                .select(AlertEvent::getId))
                .stream().map(AlertEvent::getId).collect(Collectors.toList());
        if (!alertIds.isEmpty()) {
            // 告警处置操作流水
            alertActionLogMapper.delete(
                    new LambdaQueryWrapper<AlertActionLog>().in(AlertActionLog::getAlertId, alertIds));
            // 邮件通知记录
            notificationLogMapper.delete(
                    new LambdaQueryWrapper<NotificationLog>().in(NotificationLog::getAlertId, alertIds));
        }
        // 告警事件主表
        alertEventMapper.delete(
                new LambdaQueryWrapper<AlertEvent>().eq(AlertEvent::getServerCode, serverCode));

        log.info("已级联清理服务器关联数据, serverCode={}", serverCode);
    }

    private void cleanupInfluxDB(String serverCode) {
        try {
            DeleteApi deleteApi = influxDBClient.getDeleteApi();
            OffsetDateTime start = OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC);
            OffsetDateTime stop = OffsetDateTime.now(ZoneOffset.UTC);
            String predicate = String.format("server_code=\"%s\"", serverCode);
            deleteApi.delete(start, stop, predicate, influxDBConfig.getBucket(), influxDBConfig.getOrg());
            log.info("已清理 InfluxDB 服务器时序数据, serverCode={}", serverCode);
        } catch (Exception e) {
            // InfluxDB 清理失败不阻断主流程，记录日志交由运维排查
            log.error("清理 InfluxDB 服务器时序数据失败, serverCode={}", serverCode, e);
        }
    }
}
