import { defineStore } from 'pinia'
import { login as loginApi, logout as logoutApi } from '@/api/auth'
import { STORAGE_KEYS, RoleEnum } from '@/config'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem(STORAGE_KEYS.TOKEN) || '',
    userInfo: JSON.parse(localStorage.getItem(STORAGE_KEYS.USER_INFO) || 'null'),
  }),
  getters: {
    isLoggedIn: (state) => !!state.token,
    isAdmin: (state) => state.userInfo?.role === RoleEnum.ADMIN,
    nickname: (state) => state.userInfo?.nickname || state.userInfo?.username || '用户',
  },
  actions: {
    async login(form) {
      const data = await loginApi(form)
      this.token = data.token
      this.userInfo = data.userInfo
      localStorage.setItem(STORAGE_KEYS.TOKEN, data.token)
      localStorage.setItem(STORAGE_KEYS.USER_INFO, JSON.stringify(data.userInfo))
      return data
    },
    async logout() {
      try {
        await logoutApi()
      } finally {
        this.clear()
      }
    },
    clear() {
      this.token = ''
      this.userInfo = null
      localStorage.removeItem(STORAGE_KEYS.TOKEN)
      localStorage.removeItem(STORAGE_KEYS.USER_INFO)
    },
  },
})
