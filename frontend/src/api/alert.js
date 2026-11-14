import request from './request'

/**
 * 告警管理 API（接口文档：9-告警管理）
 * 核心接口：分页列表（含自愈状态）、详情（含操作日志）、处理操作、新增告警事件上报
 */

// 分页查询告警事件列表（含自愈状态字段 healingRuleId / autoHealStatus）
export function getAlertEvents(params) {
  return request({ method: 'get', url: '/alert-events', params })
}

// 新增告警事件上报：接收客户端上报的告警信息及自愈结果，服务端落库并返回告警编号
// data 字段对齐接口文档 AlertEventCreateRequest：
// server_code / config_id / rule_id(可选) / service_name / alert_type / alert_level /
// alert_content / trigger_time / healing_rule_id / auto_heal_status /
// healing_detail / resolve_time
export function createAlertEvent(data) {
  return request({ method: 'post', url: '/alert-events', data })
}

// 查询告警详情（含自愈关联信息与操作日志）
export function getAlertEventDetail(id) {
  return request({ method: 'get', url: `/alert-events/${id}` })
}

// 告警处理操作：AUTO_HEAL 触发自愈 / MANUAL_RECOVER 手动恢复 / IGNORE 忽略
export function alertAction(id, payload) {
  return request({ method: 'post', url: `/alert-events/${id}/action`, data: payload })
}
