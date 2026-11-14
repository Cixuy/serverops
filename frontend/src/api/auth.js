import request from './request'

export function login(data) {
  return request({ method: 'post', url: '/auth/login', data })
}

export function logout() {
  return request({ method: 'post', url: '/auth/logout' })
}
