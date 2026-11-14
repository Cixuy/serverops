import request from './request'

// 获取监控配置列表
export function getMonitorConfigList(params) {
  return request({ method: 'get', url: '/monitor-configs', params })
}

// 新增监控配置
export function createMonitorConfig(data) {
  return request({ method: 'post', url: '/monitor-configs', data })
}

// 修改监控配置
export function updateMonitorConfig(id, data) {
  return request({ method: 'put', url: `/monitor-configs/${id}`, data })
}

// 删除监控配置
export function deleteMonitorConfig(id) {
  return request({ method: 'delete', url: `/monitor-configs/${id}` })
}

// 启停监控配置
export function updateMonitorConfigStatus(id, status) {
  return request({ method: 'put', url: `/monitor-configs/${id}/status`, params: { status } })
}

// 获取服务状态快照列表
export function getServiceStatusSnapshots(params) {
  return request({ method: 'get', url: '/service-status/snapshots', params })
}

// 获取服务状态看板统计
export function getServiceStatusDashboard(params) {
  return request({ method: 'get', url: '/service-status/dashboard', params })
}

// 查询某服务已绑定的自愈规则列表（接口文档：12-服务规则绑定）
// 参数：serverCode(必填) / configId(必填)
export function getServiceHealingBindings(params) {
  return request({ method: 'get', url: '/service-healing-bindings', params })
}

// 为某服务绑定自愈规则
// data: { serverCode, configId, ruleId, priority }
export function createServiceHealingBinding(data) {
  return request({ method: 'post', url: '/service-healing-bindings', data })
}

// 解除服务与规则的绑定
export function deleteServiceHealingBinding(id) {
  return request({ method: 'delete', url: `/service-healing-bindings/${id}` })
}

// 启停某绑定的监听状态（body: { status }，0-停用 1-启用）
export function toggleServiceHealingBindingStatus(id, status) {
  return request({ method: 'put', url: `/service-healing-bindings/${id}/status`, data: { status } })
}
