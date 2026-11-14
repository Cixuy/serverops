package com.qingruan.service;

import com.qingruan.vo.DashboardSummary;

public interface DashboardService {

    /**
     * 获取大屏统计概览：服务器总数 / 在线数 / 离线数，
     * 以及迭代二、三启用的告警与自愈统计（当前返回 0 占位）
     *
     * @return 大屏统计数据
     */
    DashboardSummary getDashboardSummary();
}
