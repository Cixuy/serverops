<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const phase = computed(() => route.meta.phase || '后续迭代')
</script>

<template>
  <div class="uc-page">
    <div class="uc-card">
      <div class="uc-icon">
        <svg viewBox="0 0 24 24"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" /></svg>
      </div>
      <h2>{{ route.meta.title }}</h2>
      <p>该功能模块计划于 <strong>{{ phase }}</strong> 上线，接口尚未开放。</p>
      <p class="sub">当前迭代已支持：用户认证、服务器管理、监控指标查询、数据大屏与用户管理。</p>
    </div>
  </div>
</template>

<style scoped>
.uc-page {
  display: flex;
  justify-content: center;
  padding-top: 60px;
}
.uc-card {
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: 12px;
  padding: 60px 80px;
  text-align: center;
}
.uc-icon {
  width: 72px;
  height: 72px;
  margin: 0 auto 24px;
  border-radius: 18px;
  background: #ecf5ff;
  display: flex;
  align-items: center;
  justify-content: center;
}
.uc-icon svg {
  width: 36px;
  height: 36px;
  fill: #409eff;
}
h2 {
  font-size: 20px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 12px;
}
p {
  font-size: 14px;
  color: var(--text-regular);
  line-height: 1.8;
}
p strong { color: #409eff; }
.sub {
  font-size: 12px;
  color: var(--text-secondary);
  margin-top: 8px;
}
</style>
