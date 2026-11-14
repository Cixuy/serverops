<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getServerList, deleteServer } from '@/api/server'
import { ElMessage } from '@/utils/message'
import AppPagination from '@/components/common/AppPagination.vue'
import AppSpinner from '@/components/common/AppSpinner.vue'

const router = useRouter()

const loading = ref(false)
const list = ref([])
const total = ref(0)
const query = reactive({
  pageNum: 1,
  pageSize: 10,
  status: '',
  keyword: '',
})

const MIN_DELAY = 500
function withMinDelay(promise) {
  return Promise.all([promise, new Promise((r) => setTimeout(r, MIN_DELAY))]).then(
    ([data]) => data
  )
}

async function fetchList() {
  if (loading.value) return
  loading.value = true
  try {
    const params = { pageNum: query.pageNum, pageSize: query.pageSize }
    if (query.status !== '') params.status = query.status
    if (query.keyword.trim()) params.keyword = query.keyword.trim()
    const data = await withMinDelay(getServerList(params))
    list.value = data?.list || []
    total.value = data?.total || 0
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.pageNum = 1
  fetchList()
}

function handleReset() {
  query.keyword = ''
  query.status = ''
  query.pageNum = 1
  fetchList()
}

function handlePageChange(p) {
  query.pageNum = p
  fetchList()
}

function handleSizeChange(s) {
  query.pageSize = s
  query.pageNum = 1
  fetchList()
}

/* ===== 删除 ===== */
const deleteTarget = ref(null)
const deleting = ref(false)

function askDelete(server) {
  deleteTarget.value = server
}

async function confirmDelete() {
  deleting.value = true
  try {
    await deleteServer(deleteTarget.value.id)
    ElMessage.success(`服务器 ${deleteTarget.value.serverCode} 已删除`)
    deleteTarget.value = null
    if (list.value.length === 1 && query.pageNum > 1) query.pageNum -= 1
    fetchList()
  } catch {
    /* 拦截器已提示 */
  } finally {
    deleting.value = false
  }
}

function timeAgo(dt) {
  if (!dt) return '-'
  const diff = Date.now() - new Date(dt.replace(' ', 'T')).getTime()
  if (isNaN(diff) || diff < 0) return '刚刚'
  const s = Math.floor(diff / 1000)
  if (s < 60) return `${s}秒前`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}分钟前`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h}小时前`
  return `${Math.floor(h / 24)}天前`
}

onMounted(fetchList)
</script>

<template>
  <div class="server-list">
    <div class="page-header">
      <div class="page-title">服务器列表</div>
      <div class="page-actions">
        <button class="btn btn-default" :disabled="loading" @click="fetchList">
          <AppSpinner v-if="loading" :size="14" />
          <svg v-else viewBox="0 0 24 24"><path d="M17.65 6.35A7.958 7.958 0 0012 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0112 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z" /></svg>
          {{ loading ? '刷新中...' : '刷新' }}
        </button>
      </div>
    </div>

    <!-- 筛选栏 -->
    <div class="filter-bar">
      <div class="filter-item">
        <span class="filter-label">搜索</span>
        <input
          v-model="query.keyword"
          class="filter-input"
          placeholder="输入主机名或IP地址"
          @keyup.enter="handleSearch"
        />
      </div>
      <div class="filter-item">
        <span class="filter-label">状态</span>
        <select v-model="query.status" class="filter-select" @change="handleSearch">
          <option value="">全部状态</option>
          <option :value="1">在线</option>
          <option :value="0">离线</option>
        </select>
      </div>
      <button class="btn btn-primary btn-sm" :disabled="loading" @click="handleSearch">
        <AppSpinner v-if="loading" variant="white" :size="14" />
        {{ loading ? '搜索中...' : '搜索' }}
      </button>
      <button class="btn btn-default btn-sm" :disabled="loading" @click="handleReset">重置</button>
    </div>

    <!-- 表格 -->
    <div class="table-card">
      <div class="table-toolbar">
        <div class="table-info">
          共 <strong>{{ total }}</strong> 台服务器
        </div>
        <span class="table-tip">数据由 Agent 客户端自动注册上报</span>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>服务器编码</th>
              <th>主机名</th>
              <th>IP 地址</th>
              <th>操作系统</th>
              <th>CPU / 内存</th>
              <th>Agent 版本</th>
              <th>状态</th>
              <th>最近心跳</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in list" :key="s.id">
              <td><span class="server-code">{{ s.serverCode }}</span></td>
              <td>{{ s.hostname }}</td>
              <td class="mono">{{ s.ipAddress }}</td>
              <td>
                <span class="os-icon">
                  <span class="os-dot" :class="s.osType === 'WINDOWS' ? 'windows' : 'linux'"></span>
                  {{ s.osType === 'WINDOWS' ? 'Windows' : 'Linux' }}
                  <span v-if="s.osVersion" class="os-version">{{ s.osVersion }}</span>
                </span>
              </td>
              <td class="dim">{{ [s.cpuInfo, s.memoryInfo].filter(Boolean).join(' / ') || '-' }}</td>
              <td class="mono">v{{ s.agentVersion || '-' }}</td>
              <td>
                <span class="status-dot" :class="s.status === 1 ? 'green' : 'red'"></span>
                <span class="tag" :class="s.status === 1 ? 'tag-success' : 'tag-danger'">
                  {{ s.status === 1 ? '在线' : '离线' }}
                </span>
              </td>
              <td :style="{ color: s.status === 1 ? 'var(--text-secondary)' : '#F56C6C' }">
                {{ timeAgo(s.lastHeartbeat) }}
              </td>
              <td>
                <button class="btn-text primary" @click="router.push(`/servers/${s.id}`)">详情</button>
                <button class="btn-text danger" @click="askDelete(s)">删除</button>
              </td>
            </tr>
            <tr v-if="!list.length && !loading">
              <td colspan="9">
                <div class="empty-state">
                  <svg viewBox="0 0 24 24"><path d="M4 6h16v2H4zm0 5h16v2H4zm0 5h16v2H4z" /></svg>
                  <p>暂无服务器数据，请部署 Agent 客户端并接入平台</p>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-if="loading" class="table-loading-mask">
          <AppSpinner :size="26" />
          <span>数据加载中...</span>
        </div>
      </div>
      <AppPagination
        :total="total"
        :page-num="query.pageNum"
        :page-size="query.pageSize"
        :show-size="true"
        @change="handlePageChange"
        @size-change="handleSizeChange"
      />
    </div>

    <!-- 删除确认 -->
    <div v-if="deleteTarget" class="modal-mask" @click.self="deleteTarget = null">
      <div class="modal-box confirm-box">
        <div class="modal-header">
          <h3>删除确认</h3>
          <button class="modal-close" @click="deleteTarget = null">
            <svg viewBox="0 0 24 24"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z" /></svg>
          </button>
        </div>
        <div class="modal-body">
          确定删除服务器
          <strong>{{ deleteTarget.hostname }}（{{ deleteTarget.serverCode }}）</strong>
          吗？该操作将同时清理关联的监控时序数据，且不可恢复。
        </div>
        <div class="modal-footer">
          <button class="btn btn-default" @click="deleteTarget = null">取消</button>
          <button class="btn btn-danger" :disabled="deleting" @click="confirmDelete">
            {{ deleting ? '删除中...' : '确定删除' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}
.page-title {
  font-size: 20px;
  font-weight: 600;
}
.page-actions {
  display: flex;
  gap: 10px;
}

.btn {
  padding: 9px 18px;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  border: 1px solid;
  transition: all 0.2s;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-family: inherit;
}
.btn-sm {
  padding: 7px 16px;
}
.btn-primary { background: #409eff; border-color: #409eff; color: #fff; }
.btn-primary:hover:not(:disabled) { background: #66b1ff; border-color: #66b1ff; }
.btn-default { background: var(--card-bg); border-color: var(--input-border); color: var(--text-regular); }
.btn-default:hover:not(:disabled) { color: #409eff; border-color: #c6e2ff; background: #ecf5ff; }
.btn-danger { background: #f56c6c; border-color: #f56c6c; color: #fff; }
.btn-danger:hover { background: #f78989; border-color: #f78989; }
.btn-danger:disabled { opacity: 0.6; cursor: not-allowed; }
.btn-primary:disabled { opacity: 0.7; cursor: not-allowed; }
.btn-default:disabled { opacity: 0.6; cursor: not-allowed; }
.btn svg { width: 14px; height: 14px; fill: currentColor; }

.filter-bar {
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: 10px;
  padding: 16px 20px;
  margin-bottom: 16px;
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}
.filter-item {
  display: flex;
  align-items: center;
  gap: 8px;
}
.filter-label {
  font-size: 13px;
  color: var(--text-secondary);
  white-space: nowrap;
}
.filter-input,
.filter-select {
  padding: 7px 12px;
  border: 1px solid var(--input-border);
  border-radius: 6px;
  font-size: 13px;
  color: var(--text-primary);
  outline: none;
  transition: border-color 0.2s;
  background: var(--input-bg);
  font-family: inherit;
}
.filter-input:focus,
.filter-select:focus { border-color: #409eff; }
.filter-input { width: 220px; }
.filter-select { width: 130px; }

.table-card {
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: 10px;
  overflow: hidden;
}
.table-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid var(--card-border);
}
.table-info { font-size: 13px; color: var(--text-secondary); }
.table-info strong { color: var(--text-primary); font-weight: 600; }
.table-tip { font-size: 12px; color: var(--text-disabled); }

.table-wrap {
  position: relative;
  overflow: auto;
  max-height: calc(100vh - 260px);
}
.table-loading-mask {
  position: absolute;
  inset: 0;
  background: var(--mask-bg);
  backdrop-filter: blur(1px);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  z-index: 5;
  font-size: 13px;
  color: #409eff;
}

table { width: 100%; border-collapse: collapse; }
thead th {
  text-align: left;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  padding: 12px 16px;
  background: var(--row-hover-bg);
  border-bottom: 1px solid var(--card-border);
  white-space: nowrap;
}
thead th:first-child { padding-left: 20px; }
tbody td {
  padding: 14px 16px;
  font-size: 13px;
  color: var(--text-primary);
  border-bottom: 1px solid var(--card-border);
  white-space: nowrap;
}
tbody td:first-child { padding-left: 20px; }
tbody tr:hover { background: var(--row-hover-bg); }
tbody tr:last-child td { border-bottom: none; }

.tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 10px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
}
.tag-success { background: #f0f9eb; color: #67c23a; border: 1px solid #e1f3d8; }
.tag-danger { background: #fef0f0; color: #f56c6c; border: 1px solid #fde2e2; }

.status-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 6px;
}
.status-dot.green { background: #67c23a; box-shadow: 0 0 4px rgba(103, 194, 58, 0.4); }
.status-dot.red { background: #f56c6c; box-shadow: 0 0 4px rgba(245, 108, 108, 0.4); }

.btn-text {
  background: none;
  border: none;
  font-size: 12px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 4px;
  transition: all 0.2s;
  font-family: inherit;
}
.btn-text.primary { color: #409eff; }
.btn-text.primary:hover { background: #ecf5ff; }
.btn-text.danger { color: #f56c6c; }
.btn-text.danger:hover { background: #fef0f0; }

/* 服务器编码：去除加粗/手型/悬浮下划线等额外格式，仅保留蓝色字体 */
.server-code {
  color: #409eff;
}

.mono { font-family: Consolas, Monaco, monospace; }
.dim {
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--text-regular);
}
.os-icon { display: inline-flex; align-items: center; gap: 6px; }
.os-dot { width: 8px; height: 8px; border-radius: 2px; flex-shrink: 0; }
.os-dot.windows { background: #409eff; }
.os-dot.linux { background: #67c23a; border-radius: 50%; }
.os-version { color: var(--text-secondary); font-size: 12px; }

.empty-state { text-align: center; padding: 50px 20px; }
.empty-state svg { width: 64px; height: 64px; fill: var(--text-disabled); margin-bottom: 16px; }
.empty-state p { font-size: 14px; color: var(--text-secondary); }

.confirm-box { width: 440px; }
.modal-body {
  font-size: 14px;
  color: var(--text-regular);
  line-height: 1.7;
}
</style>
