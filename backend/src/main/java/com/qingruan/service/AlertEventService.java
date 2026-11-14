package com.qingruan.service;

import com.qingruan.common.constant.AlertActionResult;
import com.qingruan.common.result.PageResult;
import com.qingruan.dto.request.AlertEventReportRequest;
import com.qingruan.vo.AlertActionRequest;
import com.qingruan.vo.AlertDetailResponse;
import com.qingruan.vo.AlertEventInfo;
import com.qingruan.vo.AlertEventReportResponse;

import java.time.LocalDateTime;

public interface AlertEventService {

    /**
     * 上报告警事件：生成告警编号，持久化告警记录
     *
     * @param request 上报告警事件请求
     * @return 上报结果（含告警ID、编号、状态）
     */
    AlertEventReportResponse reportAlertEvent(AlertEventReportRequest request);

    /**
     * 分页查询告警事件列表，支持按服务器编码、告警级别、告警状态及触发时间范围筛选
     *
     * @param pageNum     页码，从 1 开始
     * @param pageSize    每页数量
     * @param serverCode  服务器唯一编码，null 表示不筛选
     * @param alertLevel  告警级别，null 表示不筛选
     * @param alertStatus 告警状态，null 表示不筛选
     * @param startTime   触发时间下限，null 表示不限制
     * @param endTime     触发时间上限，null 表示不限制
     * @return 分页结果
     */
    PageResult<AlertEventInfo> getAlertEventList(Integer pageNum, Integer pageSize,
                                                 String serverCode, String alertLevel, String alertStatus,
                                                 LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 获取告警详情及处置时间线
     *
     * @param id 告警事件主键ID
     * @return 告警详情（含处置流水），不存在时返回 null
     */
    AlertDetailResponse getAlertDetail(Long id);

    /**
     * 对告警执行处理操作（自愈 / 手动恢复 / 搁置）
     *
     * @param id      告警事件主键ID
     * @param request 操作请求（actionType + 可选 actionDetail）
     * @return 操作结果：SUCCESS / NOT_FOUND / ALREADY_CLOSED
     */
    AlertActionResult performAction(Long id, AlertActionRequest request);
}
