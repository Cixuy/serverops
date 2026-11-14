import request from './request'

/**
 * 自愈规则管理 API（接口文档：11-自愈规则管理）
 * 核心接口：分页列表、新增、修改、删除、启停。
 * 统一使用 Result 返回结构（code=200/message/data），由 request 拦截器解包 data。
 */

// 分页查询自愈规则列表
// 参数：pageNum / pageSize / ruleName(模糊) / status(0-停用 1-启用)
export function getHealingRules(params) {
  return request({ method: 'get', url: '/healing-rules', params })
}

// 新增自愈规则
// data: { ruleName, description, triggerConfig, executeCommand, timeoutSeconds, maxRetries, retryInterval }
export function createHealingRule(data) {
  return request({ method: 'post', url: '/healing-rules', data })
}

// 修改自愈规则
// data: { ruleName, description, triggerConfig, executeCommand, timeoutSeconds, maxRetries, retryInterval }
export function updateHealingRule(id, data) {
  return request({ method: 'put', url: `/healing-rules/${id}`, data })
}

// 删除自愈规则
export function deleteHealingRule(id) {
  return request({ method: 'delete', url: `/healing-rules/${id}` })
}

// 启停自愈规则（body: { status }，0-停用 1-启用）
export function toggleHealingRuleStatus(id, status) {
  return request({ method: 'put', url: `/healing-rules/${id}/status`, data: { status } })
}

/**
 * 自愈日志管理 API（接口文档：13-自愈日志管理）
 */

// 分页查询自愈操作日志
// 参数：pageNum / pageSize / serverCode / configId / ruleId /
//       executionStatus(SUCCESS|FAILED|TIMEOUT|RETRYING) / startTime / endTime(ISO8601)
// 返回 PageResultHealingActionLog：{ total, pageNum, pageSize, list }
export function getHealingActionLogs(params) {
  return request({ method: 'get', url: '/healing-action-logs', params })
}

// 新增/补录自愈操作日志（管理端手动补录，落库 healing_action_log 表）
// data 对齐后端 HealingActionLogCreateRequest：
// 必填：serverCode / configId / ruleId / serviceName / executeCommand / executionStatus / startedAt
// 可选：alertId / triggerDetail / commandOutput / retryCount / finishedAt / durationMs
// 返回：{ id }（服务端落库后的主键ID）
export function createHealingActionLog(data) {
  return request({ method: 'post', url: '/healing-action-logs', data })
}
