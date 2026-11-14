package com.qingruan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qingruan.dto.request.ServiceStatusReportRequest;
import com.qingruan.dto.request.ServiceStatusReportRequest.ServiceStatusItem;
import com.qingruan.dto.response.PageResultServiceStatusSnapshot;
import com.qingruan.dto.response.ServiceStatusDashboard;
import com.qingruan.dto.response.ServiceStatusSnapshotInfo;
import com.qingruan.entity.ServiceStatusSnapshot;
import com.qingruan.mapper.ServiceStatusSnapshotMapper;
import com.qingruan.service.ServiceStatusSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 服务状态快照 Service 实现
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ServiceStatusSnapshotServiceImpl extends ServiceImpl<ServiceStatusSnapshotMapper, ServiceStatusSnapshot>
        implements ServiceStatusSnapshotService {

    @Override
    public void reportServiceStatus(ServiceStatusReportRequest request) {
        String serverCode = request.getServerCode();
        LocalDateTime now = LocalDateTime.now();

        for (ServiceStatusItem item : request.getServices()) {
            ServiceStatusSnapshot snapshot = toSnapshot(serverCode, item, now);
            upsertSnapshot(serverCode, item.getConfigId(), snapshot);
        }
        log.info("服务状态上报成功, serverCode={}, 项数={}", serverCode, request.getServices().size());
    }

    /**
     * 按 (server_code, config_id) 唯一约束 upsert 最新一次状态
     */
    private void upsertSnapshot(String serverCode, Long configId, ServiceStatusSnapshot snapshot) {
        LambdaQueryWrapper<ServiceStatusSnapshot> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ServiceStatusSnapshot::getServerCode, serverCode)
                .eq(ServiceStatusSnapshot::getConfigId, configId);

        ServiceStatusSnapshot existing = getOne(wrapper);
        if (existing == null) {
            save(snapshot);
        } else {
            snapshot.setId(existing.getId());
            updateById(snapshot);
        }
    }

    private ServiceStatusSnapshot toSnapshot(String serverCode, ServiceStatusItem item, LocalDateTime now) {
        ServiceStatusSnapshot snapshot = new ServiceStatusSnapshot();
        snapshot.setServerCode(serverCode);
        snapshot.setConfigId(item.getConfigId());
        snapshot.setServiceName(item.getServiceName());
        snapshot.setIsAlive(item.getIsAlive());
        snapshot.setPid(item.getPid());
        snapshot.setPortStatus(item.getPortStatus());
        snapshot.setLastCheckTime(toLocalDateTime(item.getLastCheckTime()));
        snapshot.setUpdatedAt(now);
        return snapshot;
    }

    private LocalDateTime toLocalDateTime(Long epochMillis) {
        if (epochMillis == null) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }

    @Override
    public PageResultServiceStatusSnapshot querySnapshots(Integer pageNum, Integer pageSize,
                                                            String serverCode, Integer isAlive, String keyword) {
        Page<ServiceStatusSnapshot> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<ServiceStatusSnapshot> wrapper = new LambdaQueryWrapper<>();

        if (StringUtils.hasText(serverCode)) {
            wrapper.eq(ServiceStatusSnapshot::getServerCode, serverCode);
        }
        if (isAlive != null) {
            wrapper.eq(ServiceStatusSnapshot::getIsAlive, isAlive);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.like(ServiceStatusSnapshot::getServiceName, keyword);
        }
        wrapper.orderByDesc(ServiceStatusSnapshot::getUpdatedAt);

        IPage<ServiceStatusSnapshot> result = page(page, wrapper);

        List<ServiceStatusSnapshotInfo> list = result.getRecords().stream()
                .map(this::toSnapshotInfo)
                .collect(Collectors.toList());

        PageResultServiceStatusSnapshot pageResult = new PageResultServiceStatusSnapshot();
        pageResult.setTotal(result.getTotal());
        pageResult.setPageNum((int) result.getCurrent());
        pageResult.setPageSize((int) result.getSize());
        pageResult.setList(list);
        return pageResult;
    }

    @Override
    public ServiceStatusDashboard getDashboard(String serverCode) {
        LambdaQueryWrapper<ServiceStatusSnapshot> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(serverCode)) {
            wrapper.eq(ServiceStatusSnapshot::getServerCode, serverCode);
        }
        List<ServiceStatusSnapshot> all = list(wrapper);

        int total = all.size();
        int alive = (int) all.stream().filter(s -> Integer.valueOf(1).equals(s.getIsAlive())).count();
        int down = total - alive;

        ServiceStatusDashboard dashboard = new ServiceStatusDashboard();
        dashboard.setTotalServices(total);
        dashboard.setAliveServices(alive);
        dashboard.setDownServices(down);
        dashboard.setServerCode(serverCode);
        return dashboard;
    }

    private ServiceStatusSnapshotInfo toSnapshotInfo(ServiceStatusSnapshot snapshot) {
        ServiceStatusSnapshotInfo info = new ServiceStatusSnapshotInfo();
        info.setId(snapshot.getId());
        info.setServerCode(snapshot.getServerCode());
        info.setConfigId(snapshot.getConfigId());
        info.setServiceName(snapshot.getServiceName());
        info.setIsAlive(snapshot.getIsAlive());
        info.setPid(snapshot.getPid());
        info.setPortStatus(snapshot.getPortStatus());
        info.setLastCheckTime(snapshot.getLastCheckTime());
        info.setUpdatedAt(snapshot.getUpdatedAt());
        return info;
    }
}
