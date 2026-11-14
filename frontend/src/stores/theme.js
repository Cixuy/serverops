import { defineStore } from 'pinia'
import { ref } from 'vue'

const STORAGE_KEY = 'app_theme'

export const useThemeStore = defineStore('theme', () => {
  const mode = ref(localStorage.getItem(STORAGE_KEY) === 'dark' ? 'dark' : 'light')

  function apply() {
    document.body.classList.toggle('theme-dark', mode.value === 'dark')
  }

  function toggle() {
    mode.value = mode.value === 'dark' ? 'light' : 'dark'
    localStorage.setItem(STORAGE_KEY, mode.value)
    apply()
  }

  function init() {
    apply()
  }

  return { mode, toggle, init }
})
