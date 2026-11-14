package com.qingruan.service;

import com.qingruan.common.result.PageResult;
import com.qingruan.vo.NotificationLogInfo;

public interface NotificationLogService {

    /**
     * 分页查询邮件通知记录，支持按告警ID与发送状态筛选
     *
     * @param pageNum    页码，从 1 开始
     * @param pageSize   每页数量
     * @param alertId    关联告警事件ID，null 表示不筛选
     * @param sendStatus 发送状态 0-失败 1-成功，null 表示不筛选
     * @return 分页结果
     */
    PageResult<NotificationLogInfo> getNotificationLogList(Integer pageNum, Integer pageSize,
                                                          Long alertId, Integer sendStatus);
}
