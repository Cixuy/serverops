import request from './request'

export function getUserList() {
  return request({ method: 'get', url: '/users' })
}

export function createUser(data) {
  return request({ method: 'post', url: '/users', data })
}

export function updateUser(id, data) {
  return request({ method: 'put', url: `/users/${id}`, data })
}

export function deleteUser(id) {
  return request({ method: 'delete', url: `/users/${id}` })
}
