package com.qingruan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qingruan.common.result.PageResult;
import com.qingruan.entity.NotificationLog;
import com.qingruan.mapper.NotificationLogMapper;
import com.qingruan.service.NotificationLogService;
import com.qingruan.vo.NotificationLogInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NotificationLogServiceImpl extends ServiceImpl<NotificationLogMapper, NotificationLog> implements NotificationLogService {

    @Override
    public PageResult<NotificationLogInfo> getNotificationLogList(Integer pageNum, Integer pageSize,
                                                                 Long alertId, Integer sendStatus) {
        Page<NotificationLog> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<NotificationLog> wrapper = new LambdaQueryWrapper<>();

        if (alertId != null) {
            wrapper.eq(NotificationLog::getAlertId, alertId);
        }
        if (sendStatus != null) {
            wrapper.eq(NotificationLog::getSendStatus, sendStatus);
        }
        wrapper.orderByDesc(NotificationLog::getCreatedAt);

        IPage<NotificationLog> result = page(page, wrapper);
        List<NotificationLogInfo> infos = result.getRecords().stream().map(this::toInfo).collect(Collectors.toList());
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), infos);
    }

    private NotificationLogInfo toInfo(NotificationLog log) {
        NotificationLogInfo info = new NotificationLogInfo();
        info.setId(log.getId());
        info.setAlertId(log.getAlertId());
        info.setReceiverEmails(log.getReceiverEmails());
        info.setSendStatus(log.getSendStatus());
        info.setErrorMsg(log.getErrorMsg());
        info.setCreatedAt(log.getCreatedAt());
        return info;
    }
}
