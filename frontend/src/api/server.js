import request from './request'

export function getServerList(params) {
  return request({ method: 'get', url: '/servers', params })
}

export function getServerDetail(id) {
  return request({ method: 'get', url: `/servers/${id}` })
}

export function deleteServer(id) {
  return request({ method: 'delete', url: `/servers/${id}` })
}
