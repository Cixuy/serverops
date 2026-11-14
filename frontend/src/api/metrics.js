import request from './request'

export function queryMetrics(params) {
  return request({ method: 'get', url: '/metrics/query', params })
}
