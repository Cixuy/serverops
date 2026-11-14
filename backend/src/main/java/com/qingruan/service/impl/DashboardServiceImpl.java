package com.qingruan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qingruan.entity.ServerInfo;
import com.qingruan.service.DashboardService;
import com.qingruan.service.ServerInfoService;
import com.qingruan.vo.DashboardSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final ServerInfoService serverInfoService;

    @Override
    public DashboardSummary getDashboardSummary() {
        long total = serverInfoService.count();
        long online = serverInfoService.count(new LambdaQueryWrapper<ServerInfo>().eq(ServerInfo::getStatus, 1));
        long offline = serverInfoService.count(new LambdaQueryWrapper<ServerInfo>().eq(ServerInfo::getStatus, 0));

        DashboardSummary summary = new DashboardSummary();
        summary.setTotalServers(total);
        summary.setOnlineServers(online);
        summary.setOfflineServers(offline);
        // 告警 / 自愈统计在迭代二、三启用，当前返回 0 占位
        summary.setTotalAlertsToday(0L);
        summary.setTotalHealingToday(0L);
        return summary;
    }
}
