<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { getDashboardSummary } from '@/api/dashboard'
import { getServerList } from '@/api/server'
import { getAlertEvents } from '@/api/alert'
import { queryMetrics } from '@/api/metrics'
import LineChart from '@/components/charts/LineChart.vue'
import AppSpinner from '@/components/common/AppSpinner.vue'

const router = useRouter()

/* ===== 统计概览 ===== */
const summary = ref({
  totalServers: 0,
  onlineServers: 0,
  offlineServers: 0,
  totalAlertsToday: 0,
  totalHealingToday: 0,
})
const summaryLoading = ref(false)

async function fetchSummary() {
  summaryLoading.value = true
  try {
    const data = await getDashboardSummary()
    if (data) Object.assign(summary.value, data)
  } finally {
    summaryLoading.value = false
  }
}

/* ===== 服务器列表（状态面板） ===== */
const servers = ref([])
const serversLoading = ref(false)

async function fetchServers() {
  serversLoading.value = true
  try {
    const data = await getServerList({ pageNum: 1, pageSize: 100 })
    servers.value = data?.list || []
  } finally {
    serversLoading.value = false
  }
}

function timeAgo(dt) {
  if (!dt) return '-'
  const diff = Date.now() - new Date(dt.replace(' ', 'T')).getTime()
  if (diff < 0 || isNaN(diff)) return '刚刚'
  const s = Math.floor(diff / 1000)
  if (s < 60) return `${s}秒前`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}分钟前`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h}小时前`
  return `${Math.floor(h / 24)}天前`
}

/* ===== 未处理告警滚动窗口 ===== */
const LEVEL_MAP = {
  CRITICAL: { label: '严重', cls: 'danger' },
  WARNING: { label: '警告', cls: 'warning' },
  INFO: { label: '信息', cls: 'info' },
}
const STATUS_MAP = {
  PENDING: { label: '未处理', cls: 'warning' },
  HEALING: { label: '自愈中', cls: 'info' },
  RESOLVED: { label: '已处理', cls: 'success' },
  IGNORED: { label: '已忽略', cls: 'gray' },
}

const alerts = ref([])
const alertsLoading = ref(false)
const pendingCount = ref(0)

async function fetchAlerts() {
  alertsLoading.value = true
  try {
    const data = await getAlertEvents({
      alertStatus: 'PENDING',
      pageNum: 1,
      pageSize: 100,
    })
    const list = data?.list || []
    alerts.value = list
    pendingCount.value = data?.total ?? list.length
  } catch {
    alerts.value = []
  } finally {
    alertsLoading.value = false
    updateScroll()
  }
}

function alertLevelInfo(level) {
  return LEVEL_MAP[level] || { label: level, cls: 'info' }
}
function alertStatusInfo(status) {
  return STATUS_MAP[status] || { label: status, cls: 'gray' }
}

/* 滚动窗口：固定显示 5 条，>5 条才滚动，<5 条静态显示 */
const VISIBLE_COUNT = 5
const ALERT_ROW_HEIGHT = 56

const scrollWrap = ref(null)
let scrollPos = 0
let scrollTimer = null
let scrollPaused = false

const needScroll = computed(() => alerts.value.length > VISIBLE_COUNT)

/* 仅当超过可视条数时才复制双份以实现无缝循环 */
const scrollList = computed(() =>
  needScroll.value ? alerts.value.concat(alerts.value) : alerts.value
)

function startScroll() {
  if (!needScroll.value) return
  /* 修复：先取消已有的 rAF 循环，防止每 10 秒定时刷新时重复创建动画帧，
     导致多个 tick 循环叠加、scrollPos 每帧累加多次，滚动速度越来越快 */
  stopScroll()
  scrollPaused = false
  scrollTimer = requestAnimationFrame(tick)
}
function stopScroll() {
  if (scrollTimer) cancelAnimationFrame(scrollTimer)
  scrollTimer = null
}
/* 根据当前告警数量决定是否滚动：>5 条启动滚动，否则静止并复位 */
function updateScroll() {
  if (needScroll.value) {
    startScroll()
  } else {
    stopScroll()
    scrollPos = 0
    if (scrollWrap.value) scrollWrap.value.scrollTop = 0
  }
}
function tick() {
  const el = scrollWrap.value
  if (el && needScroll.value && !scrollPaused) {
    scrollPos += 0.4
    const loop = VISIBLE_COUNT * ALERT_ROW_HEIGHT
    if (scrollPos >= loop) scrollPos -= loop
    el.scrollTop = scrollPos
  }
  scrollTimer = requestAnimationFrame(tick)
}
function onScrollEnter() {
  scrollPaused = true
}
function onScrollLeave() {
  scrollPaused = false
}

function goAlertDetail(a) {
  router.push(`/alerts/${a.id}`)
}

function fmtAlertTime(dt) {
  if (!dt) return '-'
  const d = new Date(dt.replace(' ', 'T'))
  if (isNaN(d.getTime())) return '-'
  const p = (n) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

function fmtAlertContent(content) {
  if (content == null) return '-'
  if (typeof content === 'string') return content
  try {
    return JSON.stringify(content)
  } catch {
    return String(content)
  }
}

/* ===== 资源趋势图 ===== */
const ranges = [
  { label: '30分钟', value: '30m' },
  { label: '1小时', value: '1h' },
  { label: '6小时', value: '6h' },
  { label: '24小时', value: '1d' },
]
const selectedRange = ref('1h')
const selectedServer = ref('')
const chartLoading = ref(false)
const metricsData = ref(null)

/* ===== 指标时间网格化 ===== */
// 修改：后端 /metrics/query 返回的是不规则散点（Agent 中断期间没有上报点）。
// 若直接按数组下标等距绘制，横轴会把缺数据的时间段压缩掉，导致时间轴非线性、
// 中间大量空值被直接省略。此处参照 ServerDetailView.resampleToGrid 的做法，
// 把散点重采样到覆盖整个所选时间范围的固定间隔均匀网格（5 秒基准）：
//  1) 时间轴始终按真实时间线性均匀分布、完整铺满所选范围（空值时刻也占位展示）；
//  2) 空桶（无上报点）不填充、直接置 null，折线在该处断开留缺口，
//     直观体现"该时段无数据"（不做前值填充）。
const GRID_STEP = 5000 // 网格基准步长（毫秒）
const MAX_POINTS = 4000 // 绘制点数上限，超出则自动放大步长，避免点数过多
const RANGE_SECONDS = { '30m': 1800, '1h': 3600, '6h': 21600, '1d': 86400 }

// 时间戳统一归一化为毫秒数字：兼容毫秒数字、秒级数字、ISO/本地时间字符串
function toMs(t) {
  if (t == null) return Date.now()
  if (typeof t === 'number') return t > 1e12 ? t : t * 1000
  if (typeof t === 'string') {
    const s = t.trim()
    if (/^\d+$/.test(s)) {
      const n = Number(s)
      return n > 1e12 ? n : n * 1000
    }
    const d = new Date(s.replace(' ', 'T')).getTime()
    return Number.isFinite(d) ? d : Date.now()
  }
  return Date.now()
}

// resampleToGrid：把不规则的原始散点重采样到固定均匀网格。
// 每块网格取窗口内“最新”的真实值；窗口内没有任何上报点（空桶）→ 直接置 null，
// 折线在该处断开留缺口（不做前值填充），时间轴本身仍线性完整铺满所选范围。
function resampleToGrid(points, rangeSec) {
  const spanMs = (rangeSec || RANGE_SECONDS[selectedRange.value] || 3600) * 1000
  let step = GRID_STEP
  let count = Math.ceil(spanMs / step)
  if (count > MAX_POINTS) {
    step = Math.ceil(spanMs / MAX_POINTS / GRID_STEP) * GRID_STEP
    count = Math.ceil(spanMs / step)
  }
  const timestamps = []
  const cpuUsage = []
  const memoryUsage = []
  const networkInKbps = []
  const networkOutKbps = []
  const numOrNull = (v) => (Number.isFinite(v) ? v : null)

  if (!points.length) {
    // 完全没有数据：横轴铺满所选范围，值全部留 null（LineChart 不画线，仅显示时间轴）
    const now = Date.now()
    const endBucket = Math.floor(now / step) + 1
    for (let i = 0; i < count; i++) {
      timestamps.push((endBucket - count + 1 + i) * step)
      cpuUsage.push(null)
      memoryUsage.push(null)
      networkInKbps.push(null)
      networkOutKbps.push(null)
    }
    return { timestamps, cpuUsage, memoryUsage, networkInKbps, networkOutKbps }
  }
  const maxT = Math.max(points.reduce((m, p) => Math.max(m, p.t), -Infinity), Date.now())
  const endBucket = Math.floor(maxT / step)
  const startBucket = endBucket - (count - 1)
  const buckets = new Array(count).fill(null)
  for (const p of points) {
    const b = Math.floor(p.t / step)
    if (b < startBucket || b > endBucket) continue
    buckets[b - startBucket] = p // 同桶后者覆盖，取窗口内最新值
  }
  // 修改：不做前值填充——空桶（该时段无上报点）直接置 null，
  // 折线在缺口处断开，直观体现"该时段无数据"；时间轴仍线性完整铺满所选范围。
  for (let i = 0; i < count; i++) {
    timestamps.push((startBucket + i) * step)
    const p = buckets[i]
    cpuUsage.push(numOrNull(p ? p.cpu : null))
    memoryUsage.push(numOrNull(p ? p.mem : null))
    networkInKbps.push(numOrNull(p ? p.nin : null))
    networkOutKbps.push(numOrNull(p ? p.nout : null))
  }
  return { timestamps, cpuUsage, memoryUsage, networkInKbps, networkOutKbps }
}

const perfSeries = computed(() => {
  const d = metricsData.value
  if (!d) return []
  return [
    { name: 'CPU使用率(%)', color: '#409EFF', data: d.cpuUsage || [] },
    { name: '内存使用率(%)', color: '#B37FEB', data: d.memoryUsage || [] },
  ]
})

const netSeries = computed(() => {
  const d = metricsData.value
  if (!d) return []
  return [
    { name: '网络下行(KB/s)', color: '#67C23A', data: d.networkInKbps || [], dash: true, width: 1.5 },
    { name: '网络上行(KB/s)', color: '#E6A23C', data: d.networkOutKbps || [] },
  ]
})

async function fetchMetrics() {
  if (!selectedServer.value) return
  chartLoading.value = true
  try {
    const res = await queryMetrics({
      serverCode: selectedServer.value,
      range: selectedRange.value,
    })
    if (!res) {
      metricsData.value = null
      return
    }
    // 修改：把后端返回的原始数组先转换为散点列表（兼容 snake_case / camelCase 字段，
    // 时间戳统一归一化为毫秒），再重采样成线性均匀时间网格，保证时间轴线性完整。
    const ts = Array.isArray(res.timestamps) ? res.timestamps.map(toMs) : []
    const cpu = res.cpuUsage != null ? res.cpuUsage : res.cpu_usage || []
    const mem = res.memoryUsage != null ? res.memoryUsage : res.memory_usage || []
    const nin = res.networkInKbps != null ? res.networkInKbps : res.network_in_kbps || []
    const nout = res.networkOutKbps != null ? res.networkOutKbps : res.network_out_kbps || []
    const points = []
    const n = ts.length
    for (let i = 0; i < n; i++) {
      points.push({
        t: ts[i],
        cpu: cpu[i] != null ? Number(cpu[i]) : null,
        mem: mem[i] != null ? Number(mem[i]) : null,
        nin: nin[i] != null ? Number(nin[i]) : null,
        nout: nout[i] != null ? Number(nout[i]) : null,
      })
    }
    metricsData.value = resampleToGrid(points, RANGE_SECONDS[selectedRange.value] || 3600)
  } catch {
    metricsData.value = null
  } finally {
    chartLoading.value = false
  }
}

function onServerChange() {
  fetchMetrics()
}

function onRangeChange(r) {
  selectedRange.value = r
  fetchMetrics()
}

/* ===== 定时刷新 ===== */
let timer = null
onMounted(async () => {
  await Promise.all([fetchSummary(), fetchServers(), fetchAlerts()])
  if (servers.value.length > 0) {
    selectedServer.value = servers.value[0].serverCode
    fetchMetrics()
  }
  timer = setInterval(() => {
    fetchSummary()
    fetchServers()
    fetchAlerts()
  }, 10000)
})
onUnmounted(() => {
  clearInterval(timer)
  stopScroll()
})

function goDetail(server) {
  router.push(`/servers/${server.id}`)
}

/* ===== 双图联动悬停 ===== */
const chartHover = ref(-1)

function onChartHover(i) {
  chartHover.value = i
}

/* ===== 手动刷新 ===== */
const refreshing = ref(false)

async function handleRefresh() {
  if (refreshing.value) return
  refreshing.value = true
  try {
    await Promise.all([fetchSummary(), fetchServers(), fetchAlerts()])
    await new Promise((r) => setTimeout(r, 500))
  } finally {
    refreshing.value = false
  }
}
</script>

<template>
  <div class="dashboard">
    <!-- 统计卡片 -->
    <div class="stat-row">
      <div class="stat-card blue">
        <div class="stat-header">
          <div class="stat-icon">
            <svg viewBox="0 0 24 24"><path d="M4 6h16v2H4zm0 5h16v2H4zm0 5h16v2H4z" /></svg>
          </div>
          <div>
            <div class="stat-label">在线服务器</div>
            <div class="stat-value">
              {{ summary.onlineServers }}<span class="sub"> / {{ summary.totalServers }}</span>
            </div>
            <div class="stat-trend flat">总数 {{ summary.totalServers }} 台</div>
          </div>
        </div>
        <div class="bottom-line"></div>
      </div>

      <div class="stat-card red">
        <div class="stat-header">
          <div class="stat-icon">
            <svg viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z" /></svg>
          </div>
          <div>
            <div class="stat-label">离线服务器</div>
            <div class="stat-value">{{ summary.offlineServers }}</div>
            <div class="stat-trend" :class="summary.offlineServers > 0 ? 'down' : 'up'">
              {{ summary.offlineServers > 0 ? '存在离线设备，请关注' : '全部在线' }}
            </div>
          </div>
        </div>
        <div class="bottom-line"></div>
      </div>

      <div class="stat-card orange">
        <div class="stat-header">
          <div class="stat-icon">
            <svg viewBox="0 0 24 24"><path d="M12 22a2.5 2.5 0 002-4H10a2.5 2.5 0 002 4zm6-6V11a6 6 0 10-12 0v5l-2 2v1h16v-1l-2-2z" /></svg>
          </div>
          <div>
            <div class="stat-label">今日告警</div>
            <div class="stat-value">{{ summary.totalAlertsToday }}</div>
            <div class="stat-trend flat">告警模块迭代二上线</div>
          </div>
        </div>
        <div class="bottom-line"></div>
      </div>

      <div class="stat-card green">
        <div class="stat-header">
          <div class="stat-icon">
            <svg viewBox="0 0 24 24"><path d="M17.65 6.35A7.958 7.958 0 0012 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0112 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z" /></svg>
          </div>
          <div>
            <div class="stat-label">今日自愈</div>
            <div class="stat-value">{{ summary.totalHealingToday }}</div>
            <div class="stat-trend flat">自愈模块迭代三上线</div>
          </div>
        </div>
        <div class="bottom-line"></div>
      </div>
    </div>

    <!-- 中部：趋势图 + 服务器状态 -->
    <div class="middle-row">
      <div class="chart-card">
        <div class="chart-header">
          <div class="chart-title">资源趋势监控</div>
          <div class="chart-controls">
            <select v-model="selectedServer" class="chart-select" @change="onServerChange">
              <option v-for="s in servers" :key="s.serverCode" :value="s.serverCode">
                {{ s.serverCode }} ({{ s.ipAddress }})
              </option>
            </select>
            <button
              v-for="r in ranges"
              :key="r.value"
              class="chart-btn"
              :class="{ active: selectedRange === r.value }"
              @click="onRangeChange(r.value)"
            >
              {{ r.label }}
            </button>
          </div>
        </div>
        <div v-if="chartLoading && !metricsData" class="chart-empty tall">加载中...</div>
        <div v-else-if="!selectedServer" class="chart-empty tall">暂无服务器数据，请等待 Agent 接入</div>
        <div
          v-else-if="
            !metricsData ||
            !(
              perfSeries.some((s) => s.data.some((v) => v != null)) ||
              netSeries.some((s) => s.data.some((v) => v != null))
            )
          "
          class="chart-empty tall"
        >
          该时间范围内暂无监控数据
        </div>
        <template v-else>
          <!-- 图一：CPU / 内存使用率 -->
          <div class="chart-section">
            <div class="chart-subtitle">
              <span class="legend-dot" style="background: #409eff"></span>
              <span class="legend-dot" style="background: #b37feb"></span>
              CPU / 内存使用率（%）
            </div>
            <LineChart
              :series="perfSeries"
              :timestamps="metricsData.timestamps || []"
              :height="175"
              :y-max="100"
              unit="%"
              :hover-index="chartHover"
              @hover="onChartHover"
            />
          </div>
          <!-- 图二：网络上下行 -->
          <div class="chart-section">
            <div class="chart-subtitle">
              <span class="legend-dot" style="background: #67c23a"></span>
              <span class="legend-dot" style="background: #e6a23c"></span>
              网络上行 / 下行（KB/s）
            </div>
            <LineChart
              :series="netSeries"
              :timestamps="metricsData.timestamps || []"
              :height="175"
              unit=" KB/s"
              :hover-index="chartHover"
              @hover="onChartHover"
            />
          </div>
        </template>
      </div>

      <!-- 服务器实时状态 -->
      <div class="timeline-card">
        <div class="timeline-header">
          <div class="timeline-title">服务器实时状态</div>
          <span class="timeline-badge green">{{ summary.onlineServers }} 台在线</span>
        </div>
        <div v-if="serversLoading && !servers.length" class="panel-empty">加载中...</div>
        <div v-else-if="!servers.length" class="panel-empty">暂无接入服务器</div>
        <div v-else class="server-status-list">
          <div
            v-for="s in servers.slice(0, 9)"
            :key="s.id"
            class="server-status-item"
            @click="goDetail(s)"
          >
            <span class="status-dot" :class="s.status === 1 ? 'green' : 'red'"></span>
            <div class="ss-info">
              <div class="ss-name">{{ s.hostname }}</div>
              <div class="ss-meta">{{ s.serverCode }} · {{ s.ipAddress }}</div>
            </div>
            <span class="tag" :class="s.status === 1 ? 'tag-success' : 'tag-danger'">
              {{ s.status === 1 ? '在线' : '离线' }}
            </span>
          </div>
        </div>
      </div>
    </div>

    <!-- 底部：未处理告警滚动窗口 -->
    <div class="table-card">
      <div class="table-header">
        <div class="table-title">
          未处理告警
          <span class="alert-count">{{ pendingCount }}</span>
        </div>
        <div class="table-refresh" :class="{ refreshing }" @click="handleRefresh">
          <AppSpinner v-if="refreshing" :size="14" />
          <svg v-else viewBox="0 0 24 24"><path d="M17.65 6.35A7.958 7.958 0 0012 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0112 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z" /></svg>
          {{ refreshing ? '刷新中...' : '刷新' }}
        </div>
      </div>
      <div
        v-if="alertsLoading && !alerts.length"
        class="alert-empty"
      >加载中...</div>
      <div v-else-if="!alerts.length" class="alert-empty">暂无未处理告警</div>
      <div
        v-else
        ref="scrollWrap"
        class="alert-scroll"
        @mouseenter="onScrollEnter"
        @mouseleave="onScrollLeave"
      >
        <div
          v-for="(a, i) in scrollList"
          :key="a.id + '-' + i"
          class="alert-row"
          @click="goAlertDetail(a)"
        >
          <span class="level-indicator" :class="'level-' + alertLevelInfo(a.alertLevel).cls"></span>
          <div class="alert-main">
            <div class="alert-line1">
              <span class="tag" :class="'tag-' + alertLevelInfo(a.alertLevel).cls">
                {{ alertLevelInfo(a.alertLevel).label }}
              </span>
              <span class="server-code">{{ a.serverCode || '-' }}</span>
              <span class="alert-msg" :title="fmtAlertContent(a.alertContent)">
                {{ fmtAlertContent(a.alertContent) }}
              </span>
            </div>
            <div class="alert-line2">
              <span class="alert-type">{{ a.alertRuleName || a.alertType || '-' }}</span>
              <span class="alert-time">{{ fmtAlertTime(a.triggerTime || a.createdAt) }}</span>
            </div>
          </div>
          <span class="tag" :class="'tag-' + alertStatusInfo(a.alertStatus).cls">
            {{ alertStatusInfo(a.alertStatus).label }}
          </span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dashboard {
  background: var(--page-bg);
  margin: -20px;
  padding: 20px;
  min-height: calc(100vh - 56px);
  color: var(--text-primary);
}

/* ========== 统计卡片 ========== */
.stat-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 16px;
}
.stat-card {
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: 10px;
  padding: 18px 20px;
  position: relative;
  overflow: hidden;
  transition: all 0.3s;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.03);
}
.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.08);
}
.stat-card .bottom-line {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  height: 2px;
}
.stat-card.blue .bottom-line { background: linear-gradient(90deg, #409eff, #79bbff); }
.stat-card.green .bottom-line { background: linear-gradient(90deg, #67c23a, #95de64); }
.stat-card.orange .bottom-line { background: linear-gradient(90deg, #e6a23c, #ffc069); }
.stat-card.red .bottom-line { background: linear-gradient(90deg, #f56c6c, #ff9e9e); }

.stat-header {
  display: flex;
  align-items: center;
  gap: 14px;
}
.stat-icon {
  width: 44px;
  height: 44px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.stat-card.blue .stat-icon { background: rgba(64, 158, 255, 0.1); }
.stat-card.green .stat-icon { background: rgba(103, 194, 58, 0.1); }
.stat-card.orange .stat-icon { background: rgba(230, 162, 60, 0.1); }
.stat-card.red .stat-icon { background: rgba(245, 108, 108, 0.1); }
.stat-icon svg { width: 22px; height: 22px; }
.stat-card.blue .stat-icon svg { fill: #409eff; }
.stat-card.green .stat-icon svg { fill: #67c23a; }
.stat-card.orange .stat-icon svg { fill: #e6a23c; }
.stat-card.red .stat-icon svg { fill: #f56c6c; }

.stat-label {
  font-size: 13px;
  color: var(--text-secondary);
}
.stat-value {
  font-size: 28px;
  font-weight: 700;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
  line-height: 1.25;
}
.stat-value .sub {
  font-size: 14px;
  color: var(--text-disabled);
  font-weight: 500;
}
.stat-trend {
  font-size: 12px;
  margin-top: 2px;
}
.stat-trend.up { color: #67c23a; }
.stat-trend.down { color: #f56c6c; }
.stat-trend.flat { color: var(--text-secondary); }

/* ========== 中部布局 ========== */
.middle-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 360px;
  gap: 16px;
  margin-bottom: 16px;
}
.chart-card {
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: 10px;
  padding: 18px 20px;
  min-width: 0;
}
.chart-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
  flex-wrap: wrap;
  gap: 10px;
}
.chart-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  display: flex;
  align-items: center;
  gap: 8px;
}
.chart-title::before {
  content: '';
  width: 3px;
  height: 15px;
  background: #409eff;
  border-radius: 2px;
}
.chart-controls {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.chart-btn {
  padding: 5px 12px;
  background: var(--card-bg);
  border: 1px solid var(--input-border);
  border-radius: 6px;
  color: var(--text-regular);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
  font-family: inherit;
}
.chart-btn:hover {
  border-color: #409eff;
  color: #409eff;
}
.chart-btn.active {
  background: #ecf5ff;
  border-color: #409eff;
  color: #409eff;
}
.chart-select {
  padding: 5px 12px;
  background: var(--input-bg);
  border: 1px solid var(--input-border);
  border-radius: 6px;
  color: var(--text-primary);
  font-size: 12px;
  outline: none;
  max-width: 230px;
}
.chart-select:focus {
  border-color: #409eff;
}
.chart-empty {
  height: 260px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-disabled);
  font-size: 13px;
  background:
    linear-gradient(to right, var(--grid-line) 1px, transparent 1px) 0 0 / 40px 100%,
    linear-gradient(to bottom, var(--grid-line) 1px, transparent 1px) 0 0 / 100% 52px;
  border-radius: 8px;
}
.chart-empty.tall {
  height: 430px;
}

/* ========== 双图布局 ========== */
.chart-section + .chart-section {
  margin-top: 6px;
  padding-top: 12px;
  border-top: 1px dashed var(--card-border);
}
.chart-subtitle {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  font-weight: 500;
  color: var(--text-secondary);
  margin-bottom: 4px;
}
.legend-dot {
  width: 14px;
  height: 3px;
  border-radius: 2px;
  display: inline-block;
}

/* ========== 服务器状态面板 ========== */
.timeline-card {
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: 10px;
  padding: 18px;
  overflow-y: auto;
}
.timeline-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.timeline-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  display: flex;
  align-items: center;
  gap: 8px;
}
.timeline-title::before {
  content: '';
  width: 3px;
  height: 15px;
  background: #67c23a;
  border-radius: 2px;
}
.timeline-badge {
  padding: 2px 10px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 600;
}
.timeline-badge.green {
  background: #f0f9eb;
  color: #67c23a;
  border: 1px solid #e1f3d8;
}
.panel-empty {
  text-align: center;
  color: var(--text-disabled);
  font-size: 13px;
  padding: 80px 0;
}
.server-status-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 10px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.2s;
}
.server-status-item:hover {
  background: var(--row-hover-bg);
}
.ss-info {
  flex: 1;
  min-width: 0;
}
.ss-name {
  font-size: 13px;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.ss-meta {
  font-size: 11px;
  color: var(--text-secondary);
  margin-top: 2px;
}
.status-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.status-dot.green {
  background: #67c23a;
  box-shadow: 0 0 4px rgba(103, 194, 58, 0.4);
}
.status-dot.red {
  background: #f56c6c;
  box-shadow: 0 0 4px rgba(245, 108, 108, 0.4);
  animation: pulse 1.5s infinite;
}
@keyframes pulse {
  0% { box-shadow: 0 0 0 0 rgba(245, 108, 108, 0.4); }
  70% { box-shadow: 0 0 0 8px rgba(245, 108, 108, 0); }
  100% { box-shadow: 0 0 0 0 rgba(245, 108, 108, 0); }
}

.tag {
  display: inline-flex;
  align-items: center;
  padding: 2px 9px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
}
.tag-success {
  background: #f0f9eb;
  color: #67c23a;
  border: 1px solid #e1f3d8;
}
.tag-danger {
  background: #fef0f0;
  color: #f56c6c;
  border: 1px solid #fde2e2;
}

/* ========== 表格卡片（通用） ========== */
.table-card {
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: 10px;
  padding: 18px 20px;
}
.table-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}
.table-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  display: flex;
  align-items: center;
  gap: 8px;
}
.table-title::before {
  content: '';
  width: 3px;
  height: 15px;
  background: #f56c6c;
  border-radius: 2px;
}
.alert-count {
  background: #fef0f0;
  color: #f56c6c;
  border: 1px solid #fde2e2;
  font-size: 12px;
  font-weight: 700;
  padding: 1px 9px;
  border-radius: 10px;
  font-variant-numeric: tabular-nums;
}
.table-refresh {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #409eff;
  cursor: pointer;
}
.table-refresh svg {
  width: 14px;
  height: 14px;
  fill: #409eff;
}
.table-refresh.refreshing {
  pointer-events: none;
  opacity: 0.7;
}

/* ========== 未处理告警滚动窗口 ========== */
.alert-empty {
  text-align: center;
  color: var(--text-disabled);
  font-size: 13px;
  padding: 40px 0;
}
.alert-scroll {
  height: 280px; /* 固定显示 5 条（每条 56px） */
  overflow: hidden;
  position: relative;
}
.alert-scroll::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 28px;
  background: linear-gradient(to bottom, transparent, var(--card-bg));
  pointer-events: none;
}
.alert-row {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 56px;
  box-sizing: border-box;
  padding: 0 8px;
  border-bottom: 1px solid var(--card-border);
  cursor: pointer;
  transition: background 0.2s;
}
.alert-row:hover {
  background: var(--row-hover-bg);
}
.level-indicator {
  width: 4px;
  height: 34px;
  border-radius: 2px;
  flex-shrink: 0;
}
.level-danger { background: #f56c6c; }
.level-warning { background: #e6a23c; }
.level-info { background: #409eff; }
.alert-main {
  flex: 1;
  min-width: 0;
}
.alert-line1 {
  display: flex;
  align-items: center;
  gap: 8px;
}
.server-code {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  color: var(--text-secondary);
  flex-shrink: 0;
}
.alert-msg {
  font-size: 13px;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
}
.alert-line2 {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 3px;
}
.alert-type {
  font-size: 11px;
  color: var(--text-secondary);
}
.alert-time {
  font-size: 11px;
  color: var(--text-disabled);
  font-family: Consolas, Monaco, monospace;
}
.mono { font-family: Consolas, Monaco, monospace; }
.dim { color: var(--text-secondary); max-width: 260px; overflow: hidden; text-overflow: ellipsis; }

/* ========== 响应式 ========== */
@media (max-width: 1500px) {
  .middle-row {
    grid-template-columns: minmax(0, 1fr) 320px;
  }
}
@media (max-width: 1200px) {
  .stat-row { grid-template-columns: repeat(2, 1fr); }
  .middle-row { grid-template-columns: 1fr; }
}
</style>
