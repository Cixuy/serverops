<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useThemeStore } from '@/stores/theme'
// 通知管理接口：拉取邮件通知记录，用于右上角铃铛通知面板
import { getNotificationLogs } from '@/api/notification'
const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const themeStore = useThemeStore()

const isDark = computed(() => themeStore.mode === 'dark')
const breadcrumb = computed(() => route.meta.breadcrumb || '')

const menus = [
  {
    group: '主菜单',
    items: [{ path: '/dashboard', label: '数据大屏', icon: 'dashboard' }],
  },
  {
    group: '服务器管理',
    items: [
      { path: '/servers', label: '服务器列表', icon: 'server' },
      // 服务器详情：与「告警详情」同一模式，path 指向固定入口 /servers/first，
      // 用普通 router-link 直接跳转（无异步 action），详情页内部无具体 id 时自动加载首台服务器。
      { path: '/servers/first', label: '服务器详情', icon: 'server', match: ['/servers/'] },
    ],
  },
  {
    group: '服务监控',
    items: [
      { path: '/service-config', label: '服务配置', icon: 'settings' },
      { path: '/service-status', label: '服务状态', icon: 'eye' },
    ],
  },
  {
    group: '告警与自愈',
    items: [
      { path: '/alerts', label: '告警列表', icon: 'bell' },
      { path: '/alerts/detail', label: '告警详情', icon: 'info', match: ['/alerts/detail'] },
      { path: '/healing-rules', label: '自愈规则', icon: 'refresh' },
      { path: '/healing-logs', label: '自愈日志', icon: 'doc' },
    ],
  },
  {
    group: '系统管理',
    items: [{ path: '/users', label: '用户管理', icon: 'user', adminOnly: true }],
  },
]

const visibleMenus = computed(() =>
  menus
    .map((g) => ({
      ...g,
      items: g.items.filter((it) => !it.hidden && (!it.adminOnly || userStore.isAdmin)),
    }))
    .filter((g) => g.items.length > 0)
)

function isActive(item) {
  // match 支持前缀匹配，如 /alerts/detail/5 高亮「告警详情」、/servers/xxx 高亮「服务器详情」
  if (item.match) return item.match.some((m) => route.path === m || route.path.startsWith(m))
  return route.path === item.path
}

// 所有菜单均为普通 router-link 直接跳转（由模板 to 完成），不再需要自定义 action 逻辑。
// 服务器详情通过固定入口 /servers/first 进入，页面内部无 id 时自动加载首台服务器。

const avatarChar = computed(() => userStore.nickname.charAt(0).toUpperCase())
const dropdownVisible = ref(false)
const userRef = ref(null)

function toggleDropdown() {
  dropdownVisible.value = !dropdownVisible.value
}

function onClickOutside(e) {
  if (userRef.value && !userRef.value.contains(e.target)) {
    dropdownVisible.value = false
  }
}

onMounted(() => document.addEventListener('click', onClickOutside))
onUnmounted(() => document.removeEventListener('click', onClickOutside))

async function handleLogout() {
  await userStore.logout()
  dropdownVisible.value = false
  router.push('/login')
}

// ========== 通知面板（右上角铃铛） ==========
const notifVisible = ref(false) // 通知下拉面板是否展开
const notifRef = ref(null) // 通知按钮容器，用于点击外部关闭
const notifList = ref([]) // 通知记录列表
const notifTotal = ref(0) // 通知总数（用于红点提示）
const notifLoading = ref(false) // 加载中状态

// 发送状态枚举映射（与接口文档 sendStatus 对齐：0-失败 1-成功）
const sendStatusText = { 0: '失败', 1: '成功' }

// 格式化时间：ISO 字符串 -> 本地可读时间，异常时返回原值
function formatNotifTime(val) {
  if (!val) return '--'
  const d = new Date(val)
  if (Number.isNaN(d.getTime())) return val
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

// 拉取通知记录：分页获取最新一页，用于展示与红点计数
async function loadNotifications() {
  notifLoading.value = true
  try {
    const data = await getNotificationLogs({ pageNum: 1, pageSize: 10 })
    notifList.value = data?.list || []
    notifTotal.value = data?.total ?? notifList.value.length
  } catch {
    // 请求失败（如后端未部署通知接口）时静默处理，不阻塞主页面
    notifList.value = []
  } finally {
    notifLoading.value = false
  }
}

// 切换通知面板显隐，展开时拉取最新数据
function toggleNotifications() {
  notifVisible.value = !notifVisible.value
  if (notifVisible.value) loadNotifications()
}

// 点击通知面板外部时关闭
function onNotifClickOutside(e) {
  if (notifRef.value && !notifRef.value.contains(e.target)) {
    notifVisible.value = false
  }
}

onMounted(() => {
  document.addEventListener('click', onClickOutside)
  document.addEventListener('click', onNotifClickOutside)
})
onUnmounted(() => {
  document.removeEventListener('click', onClickOutside)
  document.removeEventListener('click', onNotifClickOutside)
})
</script>

<template>
  <div class="layout" :class="{ dark: isDark }">
    <!-- 顶部栏 -->
    <header class="header">
      <div class="header-left">
        <div class="header-logo">
          <svg viewBox="0 0 24 24"><path d="M4 6h16v2H4zm0 5h16v2H4zm0 5h16v2H4z" /></svg>
        </div>
        <div class="header-title">服务智能运维平台</div>
        <div class="header-breadcrumb">/ {{ breadcrumb }}</div>
      </div>
      <div class="header-right">
        <button
          class="header-icon-btn"
          :title="isDark ? '切换为白天模式' : '切换为夜晚模式'"
          @click="themeStore.toggle()"
        >
          <svg v-if="isDark" viewBox="0 0 24 24"><path d="M6.76 4.84l-1.8-1.79-1.41 1.41 1.79 1.79 1.42-1.41zM4 10.5H1v2h3v-2zm9-9.95h-2V3.5h2V.55zm7.45 3.91l-1.41-1.41-1.79 1.79 1.41 1.41 1.79-1.79zm-3.21 13.7l1.79 1.8 1.41-1.41-1.8-1.79-1.4 1.4zM20 10.5v2h3v-2h-3zm-8-5c-3.31 0-6 2.69-6 6s2.69 6 6 6 6-2.69 6-6-2.69-6-6-6zm-1 16.95h2V19.5h-2v2.95zm-7.45-3.91l1.41 1.41 1.79-1.8-1.41-1.41-1.79 1.8z" /></svg>
          <svg v-else viewBox="0 0 24 24"><path d="M12 3c-4.97 0-9 4.03-9 9s4.03 9 9 9 9-4.03 9-9c0-.46-.04-.92-.1-1.36-.98 1.37-2.58 2.26-4.4 2.26-2.98 0-5.4-2.42-5.4-5.4 0-1.81.89-3.42 2.26-4.4-.44-.06-.9-.1-1.36-.1z" /></svg>
        </button>
        <div ref="notifRef" class="notif-wrapper">
          <button class="header-icon-btn" title="通知" @click="toggleNotifications">
            <svg viewBox="0 0 24 24"><path d="M12 22a2.5 2.5 0 002-4H10a2.5 2.5 0 002 4zm6-6V11a6 6 0 10-12 0v5l-2 2v1h16v-1l-2-2z" /></svg>
            <!-- 有通知记录时显示红点（有具体数字则显示数字角标） -->
            <span v-if="notifTotal > 0" class="badge-dot"></span>
          </button>
          <transition name="fade">
            <div v-if="notifVisible" class="notif-dropdown">
              <div class="notif-header">
                <span>通知</span>
                <button class="notif-refresh" :disabled="notifLoading" @click="loadNotifications">
                  {{ notifLoading ? '加载中…' : '刷新' }}
                </button>
              </div>
              <div class="notif-body">
                <div v-if="notifLoading" class="notif-empty">加载中…</div>
                <div v-else-if="notifList.length === 0" class="notif-empty">暂无通知</div>
                <ul v-else class="notif-list">
                  <li v-for="item in notifList" :key="item.id" class="notif-item">
                    <div class="notif-item-top">
                      <span class="notif-status" :class="item.sendStatus === 1 ? 'ok' : 'fail'">
                        {{ sendStatusText[item.sendStatus] ?? '未知' }}
                      </span>
                      <span class="notif-time">{{ formatNotifTime(item.createdAt) }}</span>
                    </div>
                    <div class="notif-receiver" :title="item.receiverEmails">{{ item.receiverEmails || '--' }}</div>
                    <div v-if="item.errorMsg" class="notif-error" :title="item.errorMsg">{{ item.errorMsg }}</div>
                  </li>
                </ul>
              </div>
            </div>
          </transition>
        </div>
        <div ref="userRef" class="user-wrapper">
          <div class="user-avatar" @click="toggleDropdown">{{ avatarChar }}</div>
          <transition name="fade">
            <div v-if="dropdownVisible" class="user-dropdown">
              <div class="dropdown-info">
                <div class="dropdown-name">{{ userStore.nickname }}</div>
                <div class="dropdown-role">
                  {{ userStore.isAdmin ? '管理员' : '操作员' }} · {{ userStore.userInfo?.username }}
                </div>
              </div>
              <button class="dropdown-item danger" @click="handleLogout">
                <svg viewBox="0 0 24 24"><path d="M17 7l-1.41 1.41L18.17 11H8v2h10.17l-2.58 2.58L17 17l5-5zM4 5h8V3H4a2 2 0 00-2 2v14a2 2 0 002 2h8v-2H4V5z" /></svg>
                退出登录
              </button>
            </div>
          </transition>
        </div>
      </div>
    </header>

    <div class="body">
      <!-- 侧边栏 -->
      <aside class="sidebar">
        <template v-for="group in visibleMenus" :key="group.group">
          <div class="menu-group-title">{{ group.group }}</div>
          <router-link
            v-for="item in group.items"
            :key="item.path"
            :to="item.path"
            class="menu-item"
            :class="{ active: isActive(item) }"
          >
            <svg v-if="item.icon === 'dashboard'" viewBox="0 0 24 24"><path d="M3 13h8V3H3v10zm0 8h8v-6H3v6zm10 0h8V11h-8v10zm0-18v6h8V3h-8z" /></svg>
            <svg v-else-if="item.icon === 'server'" viewBox="0 0 24 24"><path d="M4 6h16v2H4zm0 5h16v2H4zm0 5h16v2H4z" /></svg>
            <svg v-else-if="item.icon === 'info'" viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z" /></svg>
            <svg v-else-if="item.icon === 'settings'" viewBox="0 0 24 24"><path d="M19.14 12.94a7.07 7.07 0 000-1.88l2.03-1.58a.5.5 0 00.12-.64l-1.92-3.32a.5.5 0 00-.6-.22l-2.39.96a7.03 7.03 0 00-1.62-.94l-.36-2.54A.5.5 0 0014.14 3h-4.28a.5.5 0 00-.5.42l-.36 2.54c-.59.24-1.13.55-1.62.94l-2.39-.96a.5.5 0 00-.6.22L2.7 8.84a.5.5 0 00.12.64l2.03 1.58a7.07 7.07 0 000 1.88l-2.03 1.58a.5.5 0 00-.12.64l1.92 3.32c.14.24.43.33.68.22l2.39-.96c.49.39 1.03.7 1.62.94l.36 2.54c.05.24.25.42.5.42h4.28c.25 0 .45-.18.5-.42l.36-2.54c.59-.24 1.13-.55 1.62-.94l2.39.96c.25.11.54.02.68-.22l1.92-3.32a.5.5 0 00-.12-.64l-2.03-1.58zM12 15.5a3.5 3.5 0 110-7 3.5 3.5 0 010 7z" /></svg>
            <svg v-else-if="item.icon === 'eye'" viewBox="0 0 24 24"><path d="M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z" /></svg>
            <svg v-else-if="item.icon === 'bell'" viewBox="0 0 24 24"><path d="M12 22a2.5 2.5 0 002-4H10a2.5 2.5 0 002 4zm6-6V11a6 6 0 10-12 0v5l-2 2v1h16v-1l-2-2z" /></svg>
            <svg v-else-if="item.icon === 'refresh'" viewBox="0 0 24 24"><path d="M17.65 6.35A7.958 7.958 0 0012 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0112 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z" /></svg>
            <svg v-else-if="item.icon === 'doc'" viewBox="0 0 24 24"><path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8l-6-6zM6 20V4h7v5h5v11H6z" /></svg>
            <svg v-else viewBox="0 0 24 24"><path d="M12 12a3 3 0 100-6 3 3 0 000 6zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z" /></svg>
            {{ item.label }}
          </router-link>
        </template>
      </aside>

      <!-- 主内容 -->
      <main class="main-content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<style scoped>
.layout {
  min-height: 100vh;
}

/* ========== 顶部栏 ========== */
.header {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  position: sticky;
  top: 0;
  z-index: 100;
  background: var(--card-bg);
  border-bottom: 1px solid var(--card-border);
}
.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}
.header-logo {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: linear-gradient(135deg, #409eff, #79bbff);
  display: flex;
  align-items: center;
  justify-content: center;
}
.header-logo svg {
  width: 18px;
  height: 18px;
  fill: #fff;
}
.header-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
}
.header-breadcrumb {
  font-size: 13px;
  color: var(--text-secondary);
  margin-left: 8px;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 16px;
}
.header-icon-btn {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  border: 1px solid var(--input-border);
  background: transparent;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  position: relative;
  transition: all 0.2s;
}
.header-icon-btn:hover {
  background: var(--row-hover-bg);
}
.header-icon-btn svg {
  width: 16px;
  height: 16px;
  fill: var(--text-regular);
}
.badge-dot {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 8px;
  height: 8px;
  background: #f56c6c;
  border-radius: 50%;
  border: 2px solid var(--card-bg);
}
.notif-wrapper {
  position: relative;
}
.notif-dropdown {
  position: absolute;
  right: 0;
  top: calc(100% + 8px);
  width: 320px;
  max-height: 400px;
  display: flex;
  flex-direction: column;
  background: var(--card-bg);
  border: 1px solid var(--input-border);
  border-radius: 10px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12);
  overflow: hidden;
}
.notif-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--card-border);
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.notif-refresh {
  padding: 2px 8px;
  font-size: 12px;
  color: #409eff;
  background: none;
  border: none;
  cursor: pointer;
}
.notif-refresh:disabled {
  color: var(--text-secondary);
  cursor: not-allowed;
}
.notif-body {
  overflow-y: auto;
  flex: 1;
}
.notif-empty {
  padding: 32px 16px;
  text-align: center;
  font-size: 13px;
  color: var(--text-secondary);
}
.notif-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.notif-item {
  padding: 12px 16px;
  border-bottom: 1px solid var(--card-border);
}
.notif-item:last-child {
  border-bottom: none;
}
.notif-item-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}
.notif-status {
  font-size: 12px;
  padding: 1px 8px;
  border-radius: 4px;
}
.notif-status.ok {
  color: #67c23a;
  background: #f0f9eb;
}
.notif-status.fail {
  color: #f56c6c;
  background: #fef0f0;
}
.notif-time {
  font-size: 12px;
  color: var(--text-secondary);
}
.notif-receiver {
  font-size: 13px;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.notif-error {
  margin-top: 4px;
  font-size: 12px;
  color: #f56c6c;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.user-wrapper {
  position: relative;
}
.user-avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: linear-gradient(135deg, #409eff, #b37feb);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: 600;
  color: #fff;
  cursor: pointer;
  user-select: none;
}
.user-dropdown {
  position: absolute;
  right: 0;
  top: calc(100% + 8px);
  width: 200px;
  background: var(--card-bg);
  border: 1px solid var(--input-border);
  border-radius: 10px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12);
  overflow: hidden;
}
.dropdown-info {
  padding: 14px 16px;
  border-bottom: 1px solid var(--card-border);
}
.dropdown-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.dropdown-role {
  font-size: 12px;
  color: var(--text-secondary);
  margin-top: 2px;
}
.dropdown-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 11px 16px;
  background: none;
  border: none;
  font-size: 13px;
  color: var(--text-regular);
  cursor: pointer;
  text-align: left;
}
.dropdown-item svg {
  width: 15px;
  height: 15px;
  fill: currentColor;
}
.dropdown-item:hover {
  background: var(--row-hover-bg);
}
.dropdown-item.danger:hover {
  background: #fef0f0;
  color: #f56c6c;
}
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

/* ========== 布局 ========== */
.body {
  display: flex;
}
.sidebar {
  width: 220px;
  flex-shrink: 0;
  padding: 16px 12px;
  position: fixed;
  top: 56px;
  left: 0;
  bottom: 0;
  overflow-y: auto;
  background: var(--card-bg);
  border-right: 1px solid var(--card-border);
}
.menu-group-title {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 1.5px;
  padding: 16px 12px 8px;
  font-weight: 600;
  color: var(--text-secondary);
}
.menu-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 12px;
  border-radius: 6px;
  font-size: 14px;
  color: var(--text-regular);
  cursor: pointer;
  margin-bottom: 2px;
  transition: all 0.2s;
}
.menu-item:hover {
  background: var(--row-hover-bg);
  color: var(--text-primary);
}
.menu-item.active {
  background: #ecf5ff;
  color: #409eff;
  font-weight: 500;
  box-shadow: inset 3px 0 0 #409eff;
}
.menu-item svg {
  width: 16px;
  height: 16px;
  fill: currentColor;
  flex-shrink: 0;
}
.main-content {
  margin-left: 220px;
  padding: 20px;
  width: calc(100% - 220px);
}
</style>
