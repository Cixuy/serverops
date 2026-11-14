import request from './request'

/**
 * 通知管理 API（接口文档：10-通知管理）
 * 核心接口：分页查询邮件通知记录（含发送状态、错误信息等）
 */

// 分页查询邮件通知记录
// params 字段对齐接口文档请求参数：
// pageNum 页码 / pageSize 每页条数 / alertId 告警ID / sendStatus 发送状态(0-失败 1-成功)
export function getNotificationLogs(params) {
  return request({ method: 'get', url: '/notification-logs', params })
}
