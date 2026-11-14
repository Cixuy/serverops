import { createRouter, createWebHashHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/auth/LoginView.vue'),
    meta: { title: '登录', theme: 'dark', public: true },
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/DashboardView.vue'),
        meta: { title: '数据大屏', theme: 'light', breadcrumb: '数据大屏' },
      },
      {
        path: 'servers',
        name: 'ServerList',
        component: () => import('@/views/server/ServerListView.vue'),
        meta: { title: '服务器列表', theme: 'light', breadcrumb: '服务器管理 / 服务器列表' },
      },
      {
        // 服务器详情固定入口（侧边栏「服务器详情」菜单直接 router-link 到这里）：
        // 放在 servers/:id 之前，避免 "first" 被 :id 参数捕获；页面内部无具体 id 时自动加载首台服务器。
        path: 'servers/first',
        name: 'ServerDetailFirst',
        component: () => import('@/views/server/ServerDetailView.vue'),
        meta: { title: '服务器详情', theme: 'light', breadcrumb: '服务器管理 / 服务器详情' },
      },
      {
        path: 'servers/:id',
        name: 'ServerDetail',
        component: () => import('@/views/server/ServerDetailView.vue'),
        meta: { title: '服务器详情', theme: 'light', breadcrumb: '服务器管理 / 服务器详情' },
      },
      {
        path: 'service-config',
        name: 'ServiceConfig',
        // 服务配置页面：按原型设计落地，后端接口未开放前使用本地 mock 数据展示
        component: () => import('@/views/service/ServiceConfigView.vue'),
        meta: { title: '服务配置', theme: 'light', breadcrumb: '服务监控 / 服务配置', phase: '迭代二' },
      },
      {
        path: 'service-status',
        name: 'ServiceStatus',
        // 服务状态页面：按原型设计落地，后端接口未开放前使用本地 mock 数据展示
        component: () => import('@/views/service/ServiceStatusView.vue'),
        meta: { title: '服务状态', theme: 'light', breadcrumb: '服务监控 / 服务状态', phase: '迭代二' },
      },
      {
        path: 'alerts',
        name: 'AlertList',
        component: () => import('@/views/alert/AlertListView.vue'),
        meta: { title: '告警列表', theme: 'light', breadcrumb: '告警与自愈 / 告警列表' },
      },
      {
        // :id? 可选参数：从侧边栏进入无 id 时展示最新告警，从列表跳转带 id 展示指定告警
        path: 'alerts/detail/:id?',
        name: 'AlertDetail',
        component: () => import('@/views/alert/AlertDetailView.vue'),
        meta: { title: '告警详情', theme: 'light', breadcrumb: '告警与自愈 / 告警详情' },
      },
      {
        path: 'healing-rules',
        name: 'HealingRules',
        // 自愈规则页面：按原型设计落地，后端接口未开放前使用本地 mock 数据展示完整交互
        component: () => import('@/views/healing/HealingRulesView.vue'),
        meta: { title: '自愈规则', theme: 'light', breadcrumb: '告警与自愈 / 自愈规则', phase: '迭代三' },
      },
      {
        path: 'healing-logs',
        name: 'HealingLogs',
        // 自愈日志页面：按原型设计落地，后端接口未开放前使用本地 mock 数据展示完整交互
        component: () => import('@/views/healing/HealingLogsView.vue'),
        meta: { title: '自愈日志', theme: 'light', breadcrumb: '告警与自愈 / 自愈日志', phase: '迭代三' },
      },
      {
        path: 'users',
        name: 'UserManage',
        component: () => import('@/views/user/UserManageView.vue'),
        meta: { title: '用户管理', theme: 'light', breadcrumb: '系统管理 / 用户管理', adminOnly: true },
      },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

router.beforeEach((to) => {
  const userStore = useUserStore()
  document.title = to.meta.title
    ? `${to.meta.title} - 服务器智能运维平台`
    : '服务器智能运维平台'
  if (!to.meta.public && !userStore.isLoggedIn) {
    return { name: 'Login', query: to.fullPath !== '/' ? { redirect: to.fullPath } : {} }
  }
  if (to.name === 'Login' && userStore.isLoggedIn) {
    return { name: 'Dashboard' }
  }
  if (to.meta.adminOnly && !userStore.isAdmin) {
    return { name: 'Dashboard' }
  }
  return true
})

export default router
