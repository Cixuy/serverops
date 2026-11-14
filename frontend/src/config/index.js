/**
 * 全局配置中心：集中管理接口前缀、本地存储 key、角色枚举等
 * 修改此处即可全局生效，避免散落在各模块中的硬编码字符串。
 */

// 接口路径前缀：可通过 VITE_API_PREFIX 覆盖（默认 /api/v1）
export const API_PREFIX = import.meta.env.VITE_API_PREFIX || '/api/v1'

// 接口基础地址：前端直连后端（不走 Vite 代理）。
// 通过 VITE_API_TARGET 可覆盖（默认 http://localhost:8080），
// 例如生产环境 .env 中设置 VITE_API_TARGET=https://your-domain.com
export const API_BASE_URL = `${import.meta.env.VITE_API_TARGET || 'http://localhost:8080'}${API_PREFIX}`
// WebSocket 基础地址（直连后端，与 REST 同源端口）
export const WS_BASE_URL = `${import.meta.env.VITE_API_TARGET || 'http://localhost:8080'}`

// 本地存储 key（用户态持久化）
export const STORAGE_KEYS = {
  TOKEN: 'token',
  USER_INFO: 'userInfo',
}

// 角色枚举（与后端 UserInfo.role 对齐）
export const RoleEnum = {
  ADMIN: 'ADMIN',
  OPERATOR: 'OPERATOR',
}

// 用户状态枚举（与后端 UserUpdateRequest.status 对齐）
export const UserStatusEnum = {
  ENABLED: 0,
  DISABLED: 1,
}

// 服务器在线状态枚举（与后端 ServerInfo.status 对齐）
export const ServerStatusEnum = {
  OFFLINE: 0,
  ONLINE: 1,
}
