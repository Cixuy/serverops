import axios from 'axios'
import { ElMessage } from '@/utils/message'
import { API_BASE_URL, STORAGE_KEYS } from '@/config'
import { useUserStore } from '@/stores/user'

const request = axios.create({
  baseURL: API_BASE_URL,
  timeout: 15000,
})

// token 失效（401）时跳转登录页，避免重复触发
let redirectingToLogin = false
function redirectToLogin(message) {
  if (redirectingToLogin) return
  redirectingToLogin = true
  // 清理本地 token 与 Pinia 用户状态
  try {
    useUserStore().clear()
  } catch {
    localStorage.removeItem(STORAGE_KEYS.TOKEN)
    localStorage.removeItem(STORAGE_KEYS.USER_INFO)
  }
  ElMessage.error(message || '登录已失效，请重新登录')
  // 整页跳转以重置应用状态
  window.location.href = '/#/login'
}

request.interceptors.request.use((config) => {
  const token = localStorage.getItem(STORAGE_KEYS.TOKEN)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

request.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res && typeof res === 'object' && 'code' in res) {
      // 接口约定：code 为 0 或 200 均表示成功（见 API 文档 Result 结构）
      if (res.code !== 0 && res.code !== 200) {
        if (res.code === 401) {
          // 业务层明确返回 401（如 token 过期/失效）：跳转登录
          redirectToLogin(res.message || res.msg || '登录已失效，请重新登录')
          return Promise.reject(new Error(res.message || res.msg || '登录已失效'))
        }
        ElMessage.error(res.message || res.msg || '请求失败')
        return Promise.reject(new Error(res.message || res.msg || '请求失败'))
      }
      return res.data
    }
    return res
  },
  (error) => {
    if (error.response) {
      const { status, data } = error.response
      if (status === 401) {
        // HTTP 401（token 过期/无效，后端 JwtFilter 显式返回）：跳转登录
        redirectToLogin((data && (data.message || data.msg)) || '登录已过期，请重新登录')
      } else {
        ElMessage.error((data && (data.message || data.msg)) || `请求错误 (${status})`)
      }
    } else {
      ElMessage.error('网络异常，请检查后端服务是否可用')
    }
    return Promise.reject(error)
  }
)

export default request
