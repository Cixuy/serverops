<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from '@/utils/message'
import AppSpinner from '@/components/common/AppSpinner.vue'
import AppPagination from '@/components/common/AppPagination.vue'
import {
  getServiceStatusSnapshots,
  getServerList,
} from '@/api'

/* ============================================================
 * 服务状态页面
 * 对接后端 service-status 接口：
 *   - /service-status/snapshots  快照列表（分页 + 筛选）
 *   - /service-status/dashboard  看板统计
 * 按 serverCode 分组展示，状态以 isAlive / portStatus 为准。
 * ============================================================ */

// ===== 筛选项 =====
const query = reactive({
  keyword: '',
  serverCode: '',
  isAlive: '', // '' 全部 / 1 正常 / 0 异常
  pageNum: 1,
  pageSize: 10,
})

const loading = ref(false)
const snapshots = ref([]) // 扁平快照列表
const total = ref(0)
const servers = ref([]) // 服务器下拉选项
// 服务器在线状态映射：serverCode -> 是否在线（1 在线 / 0 离线）
const serverOnlineMap = ref({})

// 看板统计（仅统计在线服务器，离线服务器的服务不计入）
const stats = reactive({
  totalServices: 0,
  aliveServices: 0,
  downServices: 0,
})

// ===== 计算看板统计：只统计 online 的服务器下的服务 =====
function computeStats() {
  let total = 0
  let alive = 0
  for (const s of snapshots.value) {
    if (serverOnlineMap.value[s.serverCode] === 0) continue // 离线服务器跳过
    total += 1
    if (s.isAlive === 1) alive += 1
  }
  stats.totalServices = total
  stats.aliveServices = alive
  stats.downServices = total - alive
}

// ===== 加载快照列表 =====
async function loadData() {
  loading.value = true
  try {
    const params = {
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      keyword: query.keyword || undefined,
      serverCode: query.serverCode || undefined,
      isAlive: query.isAlive === '' ? undefined : Number(query.isAlive),
    }
    const res = await getServiceStatusSnapshots(params)
    snapshots.value = res.list || []
    total.value = res.total || 0
    computeStats()
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

// ===== 按 serverCode 分组（保持原型分组展示）=====
// 若某服务器离线（serverOnlineMap 标记为 0），则不展示其具体服务，只标记离线。
const groups = computed(() => {
  const map = new Map()
  for (const s of snapshots.value) {
    if (!map.has(s.serverCode)) {
      const online = serverOnlineMap.value[s.serverCode] !== 0
      map.set(s.serverCode, { code: s.serverCode, online, services: [] })
    }
    map.get(s.serverCode).services.push(s)
  }
  return [...map.values()]
})

// ===== 分组头状态点：离线服务器统一置灰显示离线，在线则组内任一异常置红，否则绿 =====
function groupStatus(g) {
  if (!g.online) return 'gray'
  if (!g.services.length) return 'gray'
  return g.services.some((s) => s.isAlive !== 1) ? 'red' : 'green'
}

function groupStat(g) {
  if (!g.online) return '服务器离线'
  const alive = g.services.filter((s) => s.isAlive === 1).length
  const down = g.services.length - alive
  return `正常 ${alive} / 异常 ${down}`
}

// ===== 单条状态展示（基于 isAlive）=====
function statusInfo(s) {
  if (s.isAlive === 1) return { dot: 'green', tag: 'tag-success', text: '正常' }
  return { dot: 'red', tag: 'tag-danger', text: '异常' }
}

function portInfo(s) {
  if (s.portStatus === 1) return { tag: 'tag-success', text: '通畅' }
  return { tag: 'tag-danger', text: '不通' }
}

// 时间格式化：ISO -> "YYYY-MM-DD HH:mm:ss"
function formatTime(t) {
  if (!t) return '-'
  return t.replace('T', ' ').replace(/Z$/, '')
}

// ===== 筛选 / 重置 / 分页 =====
function handleFilter() {
  query.pageNum = 1
  loadData()
}

function handleReset() {
  query.keyword = ''
  query.serverCode = ''
  query.isAlive = ''
  query.pageNum = 1
  loadData()
}

function handlePageChange(p) {
  query.pageNum = p
  loadData()
}

function handleSizeChange(s) {
  query.pageSize = s
  query.pageNum = 1
  loadData()
}

function handleRefresh() {
  loadData()
}

// ===== 重启（后端接口未开放，占位提示）=====
const restartTarget = ref(null)
function askRestart(s) {
  restartTarget.value = s
}
function confirmRestart() {
  ElMessage.info(`「${restartTarget.value.serviceName}」重启接口开发中，敬请期待`)
  restartTarget.value = null
}

onMounted(async () => {
  try {
    const res = await getServerList({ pageNum: 1, pageSize: 100 })
    const list = res.list || []
    servers.value = list.map((s) => ({
      code: s.serverCode,
      name: s.hostname || s.serverCode,
    }))
    // 记录每台服务器在线状态，供分组屏蔽离线服务器的服务
    const map = {}
    for (const s of list) map[s.serverCode] = s.status
    serverOnlineMap.value = map
  } catch {
    servers.value = []
  }
  loadData()
})
</script>

<template>
  <div class="service-status">
    <!-- 页头：标题 + 刷新全部 -->
    <div class="page-header">
      <div class="page-title">服务状态</div>
      <div class="page-actions">
        <button class="btn btn-default" :disabled="loading" @click="handleRefresh">
          <AppSpinner v-if="loading" :size="14" />
          <svg v-else viewBox="0 0 24 24"><path d="M17.65 6.35A7.958 7.958 0 0012 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0112 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z" /></svg>
          {{ loading ? '刷新中...' : '刷新全部' }}
        </button>
      </div>
    </div>

    <!-- 概览卡片（来自 dashboard 接口）-->
    <div class="stat-row">
      <div class="stat-card blue">
        <div class="stat-label">监控服务总数</div>
        <div class="stat-value">{{ stats.totalServices }}<small>项</small></div>
      </div>
      <div class="stat-card green">
        <div class="stat-label">运行正常</div>
        <div class="stat-value">{{ stats.aliveServices }}<small>项</small></div>
      </div>
      <div class="stat-card red">
        <div class="stat-label">已停止 / 异常</div>
        <div class="stat-value">{{ stats.downServices }}<small>项</small></div>
      </div>
    </div>

    <!-- 筛选栏 -->
    <div class="filter-bar">
      <div class="filter-item">
        <span class="filter-label">搜索服务</span>
        <input
          v-model="query.keyword"
          class="filter-input"
          placeholder="服务名称"
          @keyup.enter="handleFilter"
        />
      </div>
      <div class="filter-item">
        <span class="filter-label">所属服务器</span>
        <select v-model="query.serverCode" class="filter-select" @change="handleFilter">
          <option value="">全部服务器</option>
          <option v-for="s in servers" :key="s.code" :value="s.code">
            {{ s.code }} ({{ s.name }}){{ serverOnlineMap[s.code] === 0 ? ' [离线]' : '' }}
          </option>
        </select>
      </div>
      <div class="filter-item">
        <span class="filter-label">状态</span>
        <select v-model="query.isAlive" class="filter-select" @change="handleFilter">
          <option value="">全部状态</option>
          <option value="1">正常</option>
          <option value="0">异常</option>
        </select>
      </div>
      <button class="btn btn-primary btn-sm" @click="handleFilter">筛选</button>
      <button class="btn btn-default btn-sm" @click="handleReset">重置</button>
    </div>

    <!-- 按服务器分组展示服务状态 -->
    <div v-if="groups.length" class="server-group" v-for="g in groups" :key="g.code">
      <div class="group-header">
        <span class="status-dot" :class="groupStatus(g)"></span>
        <span class="g-name">{{ g.code }}</span>
        <span class="g-stat">{{ groupStat(g) }}</span>
      </div>

      <!-- 服务器离线：不展示具体服务，仅提示离线 -->
      <div v-if="!g.online" class="offline-tip">
        <svg viewBox="0 0 24 24"><path d="M12 2a10 10 0 100 20 10 10 0 000-20zm0 18a8 8 0 110-16 8 8 0 010 16zm-1-13h2v6h-2zm0 8h2v2h-2z" /></svg>
        <span>该服务器已离线，暂时无法获取服务状态</span>
      </div>

      <!-- 服务器在线：展示全部服务状态 -->
      <table v-else>
        <thead>
          <tr>
            <th>服务名称</th>
            <th>端口状态</th>
            <th>PID</th>
            <th>状态</th>
            <th>最近检测</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="s in g.services" :key="s.id">
            <td><strong>{{ s.serviceName }}</strong></td>
            <td><span class="tag" :class="portInfo(s).tag">{{ portInfo(s).text }}</span></td>
            <td>{{ s.pid || '-' }}</td>
            <td>
              <span class="status-dot" :class="statusInfo(s).dot"></span>
              <span class="tag" :class="statusInfo(s).tag">{{ statusInfo(s).text }}</span>
            </td>
            <td>{{ formatTime(s.lastCheckTime) }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 无匹配数据 -->
    <div v-else class="empty-state">
      <svg viewBox="0 0 24 24"><path d="M4 6h16v2H4zm0 5h16v2H4zm0 5h16v2H4z" /></svg>
      <p>暂无符合筛选条件的服务状态</p>
    </div>

    <AppPagination
      v-if="groups.length"
      :total="stats.totalServices"
      :page-num="query.pageNum"
      :page-size="query.pageSize"
      :show-size="true"
      @change="handlePageChange"
      @size-change="handleSizeChange"
    />

    <!-- 重启确认弹窗（占位） -->
    <div v-if="restartTarget" class="modal-mask" @click.self="restartTarget = null">
      <div class="modal-box confirm-box">
        <div class="modal-header">
          <h3>重启确认</h3>
          <button class="modal-close" @click="restartTarget = null">
            <svg viewBox="0 0 24 24"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z" /></svg>
          </button>
        </div>
        <div class="modal-body">
          确定对监控服务 <strong>{{ restartTarget.serviceName }}</strong>
          执行重启操作吗？重启期间该服务将短暂不可用。
        </div>
        <div class="modal-footer">
          <button class="btn btn-default" @click="restartTarget = null">取消</button>
          <button class="btn btn-danger" @click="confirmRestart">确认重启</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ===== 页头 ===== */
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}
.page-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--text-primary);
}
.page-actions {
  display: flex;
  gap: 10px;
}

/* ===== 按钮 ===== */
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
.btn-sm { padding: 7px 16px; }
.btn-primary { background: #409eff; border-color: #409eff; color: #fff; }
.btn-primary:hover { background: #66b1ff; border-color: #66b1ff; }
.btn-default { background: var(--card-bg); border-color: var(--input-border); color: var(--text-regular); }
.btn-default:hover { color: #409eff; border-color: #c6e2ff; background: #ecf5ff; }
.btn-danger { background: #f56c6c; border-color: #f56c6c; color: #fff; }
.btn-danger:hover { background: #f78989; border-color: #f78989; }
.btn:disabled { opacity: 0.6; cursor: not-allowed; }
.btn svg { width: 14px; height: 14px; fill: currentColor; }

/* ===== 概览卡片 ===== */
.stat-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}
.stat-card {
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: 10px;
  padding: 18px 20px;
}
.stat-label {
  font-size: 13px;
  color: var(--text-secondary);
  margin-bottom: 8px;
}
.stat-value {
  font-size: 26px;
  font-weight: 700;
  color: var(--text-primary);
  line-height: 1.2;
}
.stat-value small {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-secondary);
  margin-left: 4px;
}
.stat-card.blue .stat-value { color: #409eff; }
.stat-card.green .stat-value { color: #67c23a; }
.stat-card.orange .stat-value { color: #e6a23c; }
.stat-card.red .stat-value { color: #f56c6c; }

/* ===== 筛选栏 ===== */
.filter-bar {
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: 10px;
  padding: 14px 20px;
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
  background: var(--input-bg);
  font-family: inherit;
  transition: border-color 0.2s;
}
.filter-input:focus,
.filter-select:focus { border-color: #409eff; }
.filter-input { width: 200px; }
.filter-select { width: 170px; }

/* ===== 服务器分组 ===== */
.server-group {
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: 10px;
  margin-bottom: 16px;
  overflow: hidden;
}
.group-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 20px;
  border-bottom: 1px solid var(--card-border);
  background: var(--table-head-bg);
}
.g-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.g-ip {
  font-size: 12px;
  color: var(--text-secondary);
}
.g-stat {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-regular);
  white-space: nowrap;
}

/* ===== 离线提示（不展示服务） ===== */
.offline-tip {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 22px 20px;
  font-size: 13px;
  color: var(--text-secondary);
}
.offline-tip svg {
  width: 18px;
  height: 18px;
  fill: #c0c4cc;
  flex-shrink: 0;
}

/* ===== 状态点 ===== */
.status-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 6px;
}
.status-dot.green { background: #67c23a; box-shadow: 0 0 4px rgba(103, 194, 58, 0.4); }
.status-dot.red { background: #f56c6c; box-shadow: 0 0 4px rgba(245, 108, 108, 0.4); }
.status-dot.gray { background: #c0c4cc; }
.status-dot.orange { background: #e6a23c; box-shadow: 0 0 4px rgba(230, 162, 60, 0.4); }

/* ===== 表格 ===== */
table { width: 100%; border-collapse: collapse; }
thead th {
  text-align: left;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  padding: 10px 20px;
  background: var(--card-bg);
  border-bottom: 1px solid var(--card-border);
  white-space: nowrap;
}
tbody td {
  padding: 12px 20px;
  font-size: 13px;
  color: var(--text-primary);
  border-bottom: 1px solid var(--card-border);
  white-space: nowrap;
}
tbody tr:last-child td { border-bottom: none; }
tbody tr:hover { background: var(--row-hover-bg); }
.warn-time { color: #f56c6c; }
td:not(.warn-time) { color: var(--text-secondary); }

/* ===== 标签 ===== */
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
.tag-warning { background: #fdf6ec; color: #e6a23c; border: 1px solid #faecd8; }
.tag-info { background: #ecf5ff; color: #409eff; border: 1px solid #d9ecff; }

/* ===== 文本按钮 ===== */
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

/* ===== 空状态 ===== */
.empty-state {
  text-align: center;
  padding: 60px 20px;
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: 10px;
}
.empty-state svg {
  width: 64px;
  height: 64px;
  fill: var(--text-disabled);
  margin-bottom: 16px;
}
.empty-state p { font-size: 14px; color: var(--text-secondary); }

/* ===== 弹窗 ===== */
.confirm-box { width: 440px; }
</style>
