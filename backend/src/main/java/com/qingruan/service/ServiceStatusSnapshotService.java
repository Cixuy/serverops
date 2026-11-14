package com.qingruan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.qingruan.dto.request.ServiceStatusReportRequest;
import com.qingruan.dto.response.PageResultServiceStatusSnapshot;
import com.qingruan.dto.response.ServiceStatusDashboard;
import com.qingruan.entity.ServiceStatusSnapshot;

/**
 * 服务状态快照 Service
 */
public interface ServiceStatusSnapshotService extends IService<ServiceStatusSnapshot> {

    /**
     * Agent 批量上报服务状态，按 (server_code, config_id) upsert 最新快照
     *
     * @param request 上报请求
     */
    void reportServiceStatus(ServiceStatusReportRequest request);

    /**
     * 分页查询服务状态快照列表，支持按服务器、存活状态、关键字筛选
     *
     * @param pageNum    页码，从 1 开始
     * @param pageSize   每页数量
     * @param serverCode 服务器唯一编码，null 表示不筛选
     * @param isAlive    存活状态：1-正常 0-异常，null 表示不筛选
     * @param keyword    关键字，匹配 service_name，null/空表示不筛选
     * @return 分页结果
     */
    PageResultServiceStatusSnapshot querySnapshots(Integer pageNum, Integer pageSize,
                                                    String serverCode, Integer isAlive, String keyword);

    /**
     * 获取服务状态看板统计（全局或指定服务器）
     *
     * @param serverCode 服务器唯一编码，null 表示全局统计
     * @return 看板统计
     */
    ServiceStatusDashboard getDashboard(String serverCode);
}
