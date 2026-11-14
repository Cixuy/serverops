<script setup>
import { useThemeStore } from '@/stores/theme'

const themeStore = useThemeStore()
themeStore.init()
</script>

<template>
  <div class="app-root" :class="{ 'theme-dark': themeStore.mode === 'dark' }">
    <router-view />
  </div>
</template>

<style scoped>
.app-root {
  height: 100%;
  min-height: 100vh;
  background: var(--page-bg);
  color: var(--text-primary);
}
</style>
