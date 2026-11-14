import request from './request'

export function getDashboardSummary() {
  return request({ method: 'get', url: '/dashboard/summary' })
}
