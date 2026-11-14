<script setup>
import { ref, reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { ElMessage } from '@/utils/message'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const form = reactive({ username: '', password: '' })

async function handleLogin() {
  if (!form.username.trim()) return ElMessage.warning('请输入用户名')
  if (!form.password) return ElMessage.warning('请输入密码')
  loading.value = true
  try {
    await userStore.login({ username: form.username.trim(), password: form.password })
    ElMessage.success('登录成功，欢迎回来')
    router.push(route.query.redirect || '/dashboard')
  } catch {
    /* 错误提示由拦截器统一处理 */
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="bg-grid"></div>
    <div class="bg-animation"></div>

    <div class="login-wrapper">
      <div class="logo-area">
        <div class="logo-icon">
          <svg viewBox="0 0 24 24"><path d="M4 6h16v2H4zm0 5h16v2H4zm0 5h16v2H4z" /></svg>
        </div>
        <div class="system-title">服务智能运维平台</div>
        <div class="system-subtitle">SERVER INTELLIGENT OPS</div>
      </div>

      <div class="login-card">
        <h2>账号登录</h2>
        <p class="desc">请输入您的账号信息以访问系统</p>

        <form @submit.prevent="handleLogin">
          <div class="form-item">
            <label>用户名<span class="required">*</span></label>
            <div class="input-wrapper">
              <svg viewBox="0 0 24 24"><path d="M12 12a4 4 0 100-8 4 4 0 000 8zm0 2c-3.3 0-6 2.7-6 6v1h12v-1c0-3.3-2.7-6-6-6z" /></svg>
              <input
                v-model="form.username"
                class="form-input"
                type="text"
                placeholder="请输入用户名"
                autocomplete="username"
              />
            </div>
          </div>

          <div class="form-item">
            <label>密码<span class="required">*</span></label>
            <div class="input-wrapper">
              <svg viewBox="0 0 24 24"><path d="M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zM9 6c0-1.66 1.34-3 3-3s3 1.34 3 3v2H9V6zm9 14H6V10h12v10z" /></svg>
              <input
                v-model="form.password"
                class="form-input"
                type="password"
                placeholder="请输入密码"
                autocomplete="current-password"
              />
            </div>
          </div>

          <button type="submit" class="btn btn-primary login-btn" :disabled="loading">
            {{ loading ? '登 录 中 ...' : '登 录' }}
          </button>
        </form>
      </div>

      <div class="login-footer">© 2025 服务智能运维平台 · 仅供授权人员使用</div>
    </div>

    <div class="env-badge">🔒 安全环境 · v1.0.0</div>
  </div>
</template>

<style scoped>
/* 登录页背景沿用全站主题变量，明暗自动跟随 */
.login-page {
  min-height: 100vh;
  background: var(--page-bg);
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  overflow: hidden;
  color: var(--text-primary);
}
/* 品牌化网格氛围：暗色时明显、亮色时极淡，均来自主题变量 */
.bg-grid {
  position: fixed;
  inset: 0;
  z-index: 0;
  background-image:
    linear-gradient(var(--grid-line) 1px, transparent 1px),
    linear-gradient(90deg, var(--grid-line) 1px, transparent 1px);
  background-size: 60px 60px;
  mask-image: radial-gradient(ellipse 80% 60% at 50% 50%, #000 30%, transparent 80%);
  -webkit-mask-image: radial-gradient(ellipse 80% 60% at 50% 50%, #000 30%, transparent 80%);
}
/* 主色光晕，使用全站主色 #409eff 的低透明叠加 */
.bg-animation {
  position: fixed;
  inset: 0;
  z-index: 0;
  background:
    radial-gradient(ellipse 800px 600px at 20% 30%, rgba(64, 158, 255, 0.08) 0%, transparent 70%),
    radial-gradient(ellipse 600px 400px at 80% 70%, rgba(103, 194, 58, 0.05) 0%, transparent 70%),
    radial-gradient(ellipse 500px 500px at 50% 50%, rgba(64, 158, 255, 0.04) 0%, transparent 70%);
}
.login-wrapper {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 40px 16px;
}
.logo-area {
  text-align: center;
  margin-bottom: 40px;
}
.logo-icon {
  width: 64px;
  height: 64px;
  border-radius: 16px;
  background: linear-gradient(135deg, #409eff, #79bbff);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 16px;
  box-shadow: 0 0 32px rgba(64, 158, 255, 0.35);
}
.logo-icon svg {
  width: 32px;
  height: 32px;
  fill: #fff;
}
.system-title {
  font-size: 24px;
  font-weight: 700;
  color: var(--text-primary);
  letter-spacing: 2px;
}
.system-subtitle {
  font-size: 13px;
  color: var(--text-secondary);
  margin-top: 6px;
  letter-spacing: 4px;
}
/* 登录卡片对齐全站卡片规范：圆角 10px / 主题边框 / 轻投影 */
.login-card {
  width: 420px;
  max-width: 100%;
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: 10px;
  padding: 48px 40px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.12);
}
.login-card h2 {
  font-size: 20px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 8px;
}
.login-card .desc {
  font-size: 13px;
  color: var(--text-secondary);
  margin-bottom: 32px;
}
/* 表单组对齐全站 .form-item：标签用 --text-regular */
.form-item {
  margin-bottom: 20px;
}
.form-item label {
  display: block;
  font-size: 13px;
  color: var(--text-regular);
  margin-bottom: 8px;
  font-weight: 500;
}
.form-item label .required {
  color: #f56c6c;
  margin-left: 2px;
}
/* 输入框复用全局 .form-input，仅补左侧图标内边距 */
.input-wrapper {
  position: relative;
}
.input-wrapper svg {
  position: absolute;
  left: 14px;
  top: 50%;
  transform: translateY(-50%);
  width: 18px;
  height: 18px;
  fill: var(--text-secondary);
  pointer-events: none;
}
.form-input {
  padding-left: 44px;
}
/* 登录按钮对齐全站 .btn .btn-primary 规范 */
.login-btn {
  width: 100%;
  height: 38px;
  margin-top: 8px;
  letter-spacing: 4px;
}
.login-footer {
  margin-top: 28px;
  text-align: center;
  font-size: 12px;
  color: var(--text-secondary);
}
.env-badge {
  position: fixed;
  bottom: 24px;
  right: 24px;
  z-index: 1;
  padding: 6px 14px;
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: 20px;
  font-size: 12px;
  color: var(--text-secondary);
}

/* ========== 复用全站按钮规范（与其它页面 .btn 同款） ========== */
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 8px 20px;
  border: 1px solid transparent;
  border-radius: 6px;
  font-size: 14px;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.2s;
  box-sizing: border-box;
}
.btn-primary {
  background: #409eff;
  border-color: #409eff;
  color: #fff;
}
.btn-primary:hover:not(:disabled) {
  background: #66b1ff;
  border-color: #66b1ff;
}
.btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
