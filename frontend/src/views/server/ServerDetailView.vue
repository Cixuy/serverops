<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getServerDetail, getServerList } from '@/api/server'
import { queryMetrics } from '@/api/metrics'
import { getAlertEvents } from '@/api/alert'
import { getServiceStatusSnapshots } from '@/api/service'
import { getHealingActionLogs } from '@/api/healing'
import { createMetricsWs } from '@/utils/metricsWs'
import { RingBuffer } from '@/utils/ringBuffer'
import { createRafScheduler } from '@/utils/rafScheduler'
import { ElMessage } from '@/utils/message'
import LineChart from '@/components/charts/LineChart.vue'
import AppSpinner from '@/components/common/AppSpinner.vue'

const route = useRoute()
const router = useRouter()

const server = ref(null)
const loading = ref(false)
const detailError = ref('') // 详情加载失败提示：停留在详情页，不再跳回列表页

// 从侧边栏「服务器详情」进入时路由为 /servers/first（占位 id），无具体服务器 id；
// 此时先拉取首台服务器 id 再加载详情（与告警详情「无 id 拉最新一条」同一模式，避免菜单异步跳转闪跳）。
async function fetchDetail() {
  loading.value = true
  detailError.value = ''
  try {
    let id = route.params.id
    if (id === 'first' || !id) {
      const data = await getServerList({ pageNum: 1, pageSize: 1 })
      const first = data?.list?.[0]
      if (!first) {
        detailError.value = '暂无可查看的服务器，请先部署 Agent 客户端接入平台'
        server.value = null
        return
      }
      id = first.id
    }
    server.value = await getServerDetail(id)
  } catch (e) {
    // 接口失败时不再强制跳回列表页，改为提示并停留当前路由，
    // 避免点击详情后"先闪列表再回详情"的观感问题（拦截器已弹出错误 toast）。
    detailError.value = e?.message || '服务器详情加载失败，请稍后重试'
    server.value = null
  } finally {
    loading.value = false
  }
}

/* ===== 指标查询 ===== */
const ranges = [
  { label: '30分钟', value: '30m' },
  { label: '1小时', value: '1h' },
  { label: '6小时', value: '6h' },
  { label: '24小时', value: '1d' },
]
const selectedRange = ref('1h')
const metricsLoading = ref(false)

// 兼容后端历史接口(可能返回 snake_case)与 WS 推送(snake_case)两种命名，
// 统一归一化为模板使用的 camelCase，并对数值做 Number() 兜底。
function pick(obj, ...keys) {
  for (const k of keys) {
    if (obj && obj[k] != null) return obj[k]
  }
  return undefined
}

// 时间戳统一归一化为毫秒数字，兼容：毫秒数字、秒级数字、ISO/本地时间字符串
function toMs(t) {
  if (t == null) return Date.now()
  if (typeof t === 'number') {
    return t > 1e12 ? t : t * 1000 // 秒级 -> 毫秒
  }
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
function normalizeDisk(d) {
  if (!d) return null
  return {
    mountPoint: pick(d, 'mountPoint', 'mount_point') ?? '-',
    fsType: pick(d, 'fsType', 'fs_type') ?? '-',
    diskUsage: Number(pick(d, 'diskUsage', 'disk_usage')) || 0,
    diskUsedGb: Number(pick(d, 'diskUsedGb', 'disk_used_gb')) || 0,
    diskTotalGb: Number(pick(d, 'diskTotalGb', 'disk_total_gb')) || 0,
    diskReadKbps: Number(pick(d, 'diskReadKbps', 'disk_read_kbps')) || 0,
    diskWriteKbps: Number(pick(d, 'diskWriteKbps', 'disk_write_kbps')) || 0,
    diskIopsRead: Number(pick(d, 'diskIopsRead', 'disk_iops_read')) || 0,
    diskIopsWrite: Number(pick(d, 'diskIopsWrite', 'disk_iops_write')) || 0,
  }
}

// 横坐标固定按 5 秒一个网格点线性均匀分布；网格总数由所选时间范围决定，
// 某 5 秒窗口无真实数据则该点置 0。
const GRID_STEP = 5000
// 各时间范围对应的秒数（30分钟/1小时/6小时/24小时）
const RANGE_SECONDS = { '30m': 1800, '1h': 3600, '6h': 21600, '1d': 86400 }
// 绘制点数上限，超出则自动放大步长（仍均匀覆盖整个范围），避免 24h 点数过多
const MAX_POINTS = 4000

// 渲染用：重采样后的均匀网格数据（与图表 series 一一对应）
const gridData = ref({ timestamps: [], cpuUsage: [], memoryUsage: [], networkInKbps: [], networkOutKbps: [] })
// 数据点队列：{ t, cpu, mem, nin, nout }，按时间升序。
// 历史数据（fetchMetrics）与实时推送（appendServerMetrics）都进入同一队列；
// 使用环形缓冲区替代普通数组：预分配固定容量，push 满即覆盖最旧元素，
// 消除 shift()/slice() 带来的 O(n) 内存重分配与 GC 抖动。
// 容量按 MAX_POINTS 上限给 1.5 倍余量，容忍断线重连瞬间的乱序涌入。
let pointsQueue = new RingBuffer(Math.ceil(MAX_POINTS * 1.5))
// 磁盘用最新一组覆盖，不进时间序列网格
const diskData = ref([])
// rAF 合并调度器：同一帧内的多次 appendServerMetrics 只触发一次 rebuildGrid
let gridScheduler = null

// resampleToGrid：把不规则的原始散点重采样到固定均匀网格（步长 GRID_STEP 毫秒，
// 点数超出上限时自动放大步长）。每块网格取窗口内“最新”的真实值；
// 窗口内没有任何上报点（空桶）→ 直接置 null（不做前值填充）。
// 小规模空值由 LineChart 组件内部按 maxFillGap 阈值做前值填充、保持曲线连续，
// 大规模空值则断开留缺口，直观体现“该时段无数据”——与大屏 DashboardView 绘制方案一致。
function resampleToGrid(points, rangeSec) {
  // rangeSec 为“秒”，GRID_STEP 为“毫秒”，统一换算为毫秒后再计算网格点数
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
    // 完全没有数据：横轴铺满所选范围，值全部留 null（折线不绘制，仅显示时间轴）
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
  // 修改：不做前值填充——空桶（该时段无上报点）直接置 null。
  // 小规模空值（连续空桶 ≤ LineChart 的 maxFillGap）由组件层前值填充保持曲线连续，
  // 大规模空值保留 null 使折线断开留缺口，直观体现“该时段无数据”。
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

function rebuildGrid() {
  // toSortedArray() 仅在每帧重建一次（由 rAF 调度），而非每条消息一次
  const snapshot = pointsQueue.toSortedArray()
  gridData.value = resampleToGrid(snapshot, RANGE_SECONDS[selectedRange.value] || 3600)
}

async function fetchMetrics() {
  if (!server.value?.serverCode) return
  metricsLoading.value = true
  try {
    const res = await queryMetrics({
      serverCode: server.value.serverCode,
      range: selectedRange.value,
    })
    if (res) {
      fillCamelKeys(res)
      const ts = Array.isArray(res.timestamps) ? res.timestamps.map(toMs) : []
      const cpu = res.cpuUsage || []
      const mem = res.memoryUsage || []
      const nin = res.networkInKbps || []
      const nout = res.networkOutKbps || []
      const n = ts.length
      pointsQueue.clear()
      for (let i = 0; i < n; i++) {
        pointsQueue.push({
          t: ts[i],
          cpu: cpu[i] != null ? Number(cpu[i]) : null,
          mem: mem[i] != null ? Number(mem[i]) : null,
          nin: nin[i] != null ? Number(nin[i]) : null,
          nout: nout[i] != null ? Number(nout[i]) : null,
        })
      }
      // trimQueue 不再需要：RingBuffer 满即覆盖最旧点，自动淘汰超出窗口的过期数据
      if (Array.isArray(res.disks)) {
        diskData.value = res.disks.map(normalizeDisk).filter(Boolean)
      }
      rebuildGrid()
    }
  } catch {
    pointsQueue.clear()
    diskData.value = []
    rebuildGrid()
  } finally {
    metricsLoading.value = false
  }
}

function onRangeChange(r) {
  selectedRange.value = r
  // 切换时间范围：RingBuffer 按容量自动淘汰最旧点，无需显式 trimQueue
  fetchMetrics()
}

/* ===== 实时指标（WebSocket 推送） ===== */
// 历史数据先用 /api/v1/metrics/query 拉取（见 onMounted），
// 之后通过 WebSocket 订阅实时指标，推送点入队后重采样到所选时间段的网格。
const REAL_TIME_METRICS = ['server_metrics', 'server_disk_metrics']
let metricsWs = null

// 基础指标字段 snake_case -> camelCase 兼容（历史接口可能返回 snake_case）
const BASE_KEY_MAP = {
  cpu_usage: 'cpuUsage',
  memory_usage: 'memoryUsage',
  network_in_kbps: 'networkInKbps',
  network_out_kbps: 'networkOutKbps',
}
function fillCamelKeys(obj) {
  if (!obj) return
  for (const [snake, camel] of Object.entries(BASE_KEY_MAP)) {
    if (obj[camel] == null && obj[snake] != null) obj[camel] = obj[snake]
  }
}

// 基础指标推送：snake_case -> 数据点，O(1) 入环形缓冲区；过期淘汰由 RingBuffer 自动完成。
// 同一帧内的多次调用通过 rAF 调度器合并为一次 rebuildGrid，避免断线重连瞬间掉帧。
function appendServerMetrics(msg) {
  if (!msg?.data) return
  const d = msg.data
  fillCamelKeys(d)
  pointsQueue.push({
    t: toMs(msg.timestamp),
    cpu: d.cpu_usage != null ? Number(d.cpu_usage) : d.cpuUsage != null ? Number(d.cpuUsage) : null,
    mem: d.memory_usage != null ? Number(d.memory_usage) : d.memoryUsage != null ? Number(d.memoryUsage) : null,
    nin: d.network_in_kbps != null ? Number(d.network_in_kbps) : d.networkInKbps != null ? Number(d.networkInKbps) : null,
    nout: d.network_out_kbps != null ? Number(d.network_out_kbps) : d.networkOutKbps != null ? Number(d.networkOutKbps) : null,
  })
  // 标记脏，交由 rAF 在下一渲染帧合并执行一次 rebuildGrid
  gridScheduler.schedule()
}

// 磁盘指标推送：整组覆盖（data 为数组），归一化字段名
function appendDiskMetrics(msg) {
  if (!Array.isArray(msg?.data)) return
  diskData.value = msg.data.map(normalizeDisk).filter(Boolean)
}

// WS 连接状态：connecting | open | reconnecting | closed
const wsStatus = ref('connecting')
const wsStatusText = {
  connecting: '连接中...',
  open: '实时已连接',
  reconnecting: '重连中...',
  closed: '已断开',
}
// 最近一次收到推送的时间，用于直观判断是否实时
const lastPushAt = ref('')

function touchPush() {
  const d = new Date()
  lastPushAt.value = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`
}

function setupMetricsWs() {
  if (!server.value?.serverCode) return
  metricsWs = createMetricsWs({
    onServerMetrics: (msg) => {
      appendServerMetrics(msg)
      touchPush()
    },
    onDiskMetrics: (msg) => {
      appendDiskMetrics(msg)
      touchPush()
    },
    onError: (msg) => {
      ElMessage.warning(msg?.message || '实时指标订阅异常')
    },
    onStatus: (s) => {
      wsStatus.value = s
    },
  })
  metricsWs.connect()
  metricsWs.subscribe([server.value.serverCode], REAL_TIME_METRICS)
}

/* ===== 手动刷新（带按钮动效，自动刷新不触发） ===== */
const detailRefreshing = ref(false)

async function handleRefreshDetail() {
  if (detailRefreshing.value) return
  detailRefreshing.value = true
  try {
    await Promise.all([fetchDetail(), new Promise((r) => setTimeout(r, 500))])
  } finally {
    detailRefreshing.value = false
  }
}

const metricsRefreshing = ref(false)

async function handleRefreshMetrics() {
  if (metricsRefreshing.value) return
  metricsRefreshing.value = true
  try {
    await Promise.all([fetchMetrics(), new Promise((r) => setTimeout(r, 500))])
  } finally {
    metricsRefreshing.value = false
  }
}

// 修改：网格化后数组长度恒 > 0（即使全为 null），故改为判断是否存在任一有效值，
// 保证确实无数据时显示空状态而非空白图表
const hasData = computed(
  () =>
    (gridData.value?.cpuUsage || []).some((v) => v != null) ||
    (gridData.value?.memoryUsage || []).some((v) => v != null) ||
    (gridData.value?.networkInKbps || []).some((v) => v != null)
)

function statOf(arr) {
  const valid = (arr || []).filter((v) => v != null)
  if (!valid.length) return { cur: '-', avg: '-', max: '-' }
  const cur = valid[valid.length - 1]
  const avg = valid.reduce((a, b) => a + b, 0) / valid.length
  const max = Math.max(...valid)
  return { cur, avg, max }
}
const cpuStat = computed(() => statOf(gridData.value?.cpuUsage))
const memStat = computed(() => statOf(gridData.value?.memoryUsage))

const cpuSeries = computed(() => [
  { name: 'CPU使用率(%)', color: '#409EFF', data: gridData.value?.cpuUsage || [] },
])
const memSeries = computed(() => [
  { name: '内存使用率(%)', color: '#B37FEB', data: gridData.value?.memoryUsage || [] },
])
const netSeries = computed(() => [
  { name: '下行(KB/s)', color: '#67C23A', data: gridData.value?.networkInKbps || [], dash: true, width: 1.5 },
  { name: '上行(KB/s)', color: '#E6A23C', data: gridData.value?.networkOutKbps || [] },
])

const disks = computed(() => diskData.value || [])

function fmt(v, unit) {
  if (v == null) return '-'
  return `${Number(v).toFixed(1)}${unit}`
}

onMounted(async () => {
  // 创建 rAF 合并调度器（同一帧多次 append 只触发一次 rebuildGrid）
  gridScheduler = createRafScheduler(rebuildGrid)
  // 1. 首次查询历史指标数据并展示
  await fetchDetail()
  fetchMetrics()
  // 2. 建立 WebSocket 订阅，后续实时数据由推送追加（不再轮询）
  setupMetricsWs()
})
onUnmounted(() => {
  gridScheduler?.cancel()
  if (metricsWs) {
    if (server.value?.serverCode) metricsWs.unsubscribe([server.value.serverCode])
    metricsWs.close()
    metricsWs = null
  }
})

/* ===== 历史告警（复用告警管理模块接口 getAlertEvents，按 serverCode 筛选） ===== */
const activeTab = ref('metrics') // 默认展示实时指标；历史告警按需加载
const alertLoading = ref(false)
const alertList = ref([])
const alertTotal = ref(0)
const alertPage = ref(1)
const alertPageSize = ref(10)

// 与告警列表/详情页保持一致的展示映射，避免风格割裂
const ALERT_LEVEL_MAP = {
  CRITICAL: { label: '严重', cls: 'danger' },
  WARNING: { label: '警告', cls: 'warning' },
  INFO: { label: '信息', cls: 'info' },
}
const ALERT_STATUS_MAP = {
  PENDING: { label: '未处理', cls: 'warning' },
  HEALING: { label: '自愈中', cls: 'info' },
  RESOLVED: { label: '已处理', cls: 'success' },
  IGNORED: { label: '已忽略', cls: 'gray' },
}
function alertMap(map, value) {
  return map[value] || { label: value || '-', cls: 'gray' }
}

async function loadAlertHistory(page = 1) {
  const code = server.value?.serverCode
  if (!code) return
  alertPage.value = page
  alertLoading.value = true
  try {
    const data = await getAlertEvents({
      serverCode: code,
      pageNum: page,
      pageSize: alertPageSize.value,
    })
    alertList.value = data?.list || []
    alertTotal.value = data?.total ?? alertList.value.length
  } catch {
    alertList.value = []
    alertTotal.value = 0 // 查询失败展示空态，拦截器已提示
  } finally {
    alertLoading.value = false
  }
}

// 切换 Tab：首次进入历史告警/服务列表/自愈记录时各拉取一次，避免每次重入详情页都请求
function onTabChange(tab) {
  activeTab.value = tab
  if (tab === 'alerts' && alertList.value.length === 0 && !alertLoading.value) {
    loadAlertHistory(1)
  }
  if (tab === 'services' && serviceList.value.length === 0 && !serviceLoading.value) {
    loadServices(1)
  }
  if (tab === 'healings' && healingList.value.length === 0 && !healingLoading.value) {
    loadHealingHistory(1)
  }
}

/* ===== 自愈记录（复用自愈日志模块接口 getHealingActionLogs，按 serverCode 筛选） ===== */
const healingLoading = ref(false)
const healingList = ref([])
const healingTotal = ref(0)
const healingPage = ref(1)
const healingPageSize = ref(10)

// 自愈执行结果映射（对齐 executionStatus 枚举）
const HEALING_STATUS_MAP = {
  SUCCESS: { label: '成功', cls: 'success' },
  FAILED: { label: '失败', cls: 'danger' },
  TIMEOUT: { label: '超时', cls: 'danger' },
  RETRYING: { label: '重试中', cls: 'warning' },
}

// 时间格式化：ISO8601 → 本地可读时间
function healTime(iso) {
  if (!iso) return '-'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

// triggerDetail 为 object 快照，序列化为字符串展示
function healTriggerDetail(d) {
  if (d == null) return '-'
  if (typeof d === 'string') return d || '-'
  try {
    return JSON.stringify(d)
  } catch {
    return String(d)
  }
}

async function loadHealingHistory(page = 1) {
  const code = server.value?.serverCode
  if (!code) return
  healingPage.value = page
  healingLoading.value = true
  try {
    const data = await getHealingActionLogs({
      serverCode: code,
      pageNum: page,
      pageSize: healingPageSize.value,
    })
    healingList.value = data?.list || []
    healingTotal.value = data?.total ?? healingList.value.length
  } catch {
    healingList.value = []
    healingTotal.value = 0 // 查询失败展示空态，拦截器已提示
  } finally {
    healingLoading.value = false
  }
}

/* ===== 服务列表（复用服务监控模块快照接口，按当前 serverCode 筛选） ===== */
const serviceLoading = ref(false)
const serviceList = ref([])
const servicePage = ref(1)
const servicePageSize = ref(10)
const serviceTotal = ref(0)

function serviceStatusInfo(s) {
  if (s.isAlive === 1) return { dot: 'green', tag: 'tag-success', text: '正常' }
  return { dot: 'red', tag: 'tag-danger', text: '异常' }
}
function servicePortInfo(s) {
  if (s.portStatus === 1) return { tag: 'tag-success', text: '通畅' }
  return { tag: 'tag-danger', text: '不通' }
}
function fmtCheckTime(t) {
  if (!t) return '-'
  return t.replace('T', ' ').replace(/Z$/, '')
}

async function loadServices(page = 1) {
  const code = server.value?.serverCode
  if (!code) return
  servicePage.value = page
  serviceLoading.value = true
  try {
    const res = await getServiceStatusSnapshots({
      serverCode: code,
      pageNum: page,
      pageSize: servicePageSize.value,
    })
    serviceList.value = res?.list || []
    serviceTotal.value = res?.total ?? serviceList.value.length
  } catch {
    serviceList.value = []
    serviceTotal.value = 0 // 查询失败展示空态，拦截器已提示
  } finally {
    serviceLoading.value = false
  }
}
</script>

<template>
  <div class="server-detail">
    <a class="back-link" @click="router.push('/servers')">
      <svg viewBox="0 0 24 24"><path d="M15.41 7.41L14 6l-6 6 6 6 1.41-1.41L10.83 12z" /></svg>
      返回服务器列表
    </a>

    <!-- 详情加载失败提示：停留在详情页，不跳回列表 -->
    <div v-if="detailError" class="detail-error">
      <span>{{ detailError }}</span>
      <button class="btn btn-default" @click="fetchDetail">重试</button>
    </div>

    <!-- 信息卡片 -->
    <div class="info-card">
      <div class="info-header">
        <div class="info-title">
          <h2>{{ server?.serverCode }} / {{ server?.hostname }}</h2>
          <span v-if="server" class="status-dot" :class="server.status === 1 ? 'green' : 'red'"></span>
          <span v-if="server" class="tag" :class="server.status === 1 ? 'tag-success' : 'tag-danger'">
            {{ server.status === 1 ? '在线' : '离线' }}
          </span>
          <span v-if="server?.osVersion" class="tag tag-info">{{ server.osVersion }}</span>
        </div>
        <div style="display: flex; gap: 8px">
          <button class="btn btn-default" :disabled="detailRefreshing" @click="handleRefreshDetail">
            <AppSpinner v-if="detailRefreshing" :size="14" />
            {{ detailRefreshing ? '刷新中...' : '刷新信息' }}
          </button>
          <button class="btn btn-primary" :disabled="metricsRefreshing" @click="handleRefreshMetrics">
            <AppSpinner v-if="metricsRefreshing" variant="white" :size="14" />
            {{ metricsRefreshing ? '刷新中...' : '刷新数据' }}
          </button>
        </div>
      </div>
      <div class="info-grid">
        <div class="info-item"><label>IP 地址</label><span class="value mono">{{ server?.ipAddress || '-' }}</span></div>
        <div class="info-item"><label>操作系统</label><span class="value">{{ server ? `${server.osType === 'WINDOWS' ? 'Windows' : 'Linux'}${server.osVersion ? ' · ' + server.osVersion : ''}` : '-' }}</span></div>
        <div class="info-item"><label>CPU</label><span class="value">{{ server?.cpuInfo || '-' }}</span></div>
        <div class="info-item"><label>内存</label><span class="value">{{ server?.memoryInfo || '-' }}</span></div>
        <div class="info-item"><label>磁盘</label><span class="value">{{ server?.diskInfo || '-' }}</span></div>
        <div class="info-item"><label>Agent 版本</label><span class="value mono">{{ server?.agentVersion ? 'v' + server.agentVersion : '-' }}</span></div>
        <div class="info-item"><label>最近心跳</label><span class="value" :style="{ color: server?.status === 1 ? '#67C23A' : '#F56C6C' }">{{ server?.lastHeartbeat || '-' }}</span></div>
        <div class="info-item"><label>接入时间</label><span class="value">{{ server?.createdAt || '-' }}</span></div>
      </div>
    </div>

    <!-- Tabs -->
    <div class="tabs">
      <div class="tab" :class="{ active: activeTab === 'metrics' }" @click="onTabChange('metrics')">实时监控</div>
      <div class="tab" :class="{ active: activeTab === 'services' }" @click="onTabChange('services')">服务列表</div>
      <div class="tab" :class="{ active: activeTab === 'alerts' }" @click="onTabChange('alerts')">历史告警</div>
      <div class="tab" :class="{ active: activeTab === 'healings' }" @click="onTabChange('healings')">自愈记录</div>
    </div>

    <!-- 时间范围选择（仅实时监控 Tab 展示） -->
    <div class="range-bar" v-if="activeTab === 'metrics'">
      <span class="range-label">时间范围：</span>
      <button
        v-for="r in ranges"
        :key="r.value"
        class="chart-btn"
        :class="{ active: selectedRange === r.value }"
        @click="onRangeChange(r.value)"
      >
        {{ r.label }}
      </button>
      <span v-if="metricsLoading" class="loading-tip">数据刷新中...</span>
      <span class="ws-status" :class="'ws-' + wsStatus">
        <i class="ws-dot"></i>{{ wsStatusText[wsStatus] }}
        <template v-if="lastPushAt && wsStatus === 'open'">· 最近推送 {{ lastPushAt }}</template>
      </span>
    </div>

    <!-- 图表区（仅实时监控 Tab 展示） -->
    <div class="charts-row" v-if="activeTab === 'metrics'">
      <div class="chart-card">
        <div class="chart-card-title">CPU 使用率</div>
        <div v-if="!hasData" class="chart-empty">暂无数据</div>
        <LineChart v-else :series="cpuSeries" :timestamps="gridData.timestamps" :height="200" unit="%" />
        <div class="chart-footer">
          <span>当前: <strong style="color: #409eff">{{ fmt(cpuStat.cur, '%') }}</strong></span>
          <span>平均: {{ fmt(cpuStat.avg, '%') }}</span>
          <span>峰值: {{ fmt(cpuStat.max, '%') }}</span>
        </div>
      </div>

      <div class="chart-card">
        <div class="chart-card-title">内存使用率</div>
        <div v-if="!hasData" class="chart-empty">暂无数据</div>
        <LineChart v-else :series="memSeries" :timestamps="gridData.timestamps" :height="200" unit="%" />
        <div class="chart-footer">
          <span>当前: <strong style="color: #b37feb">{{ fmt(memStat.cur, '%') }}</strong></span>
          <span>平均: {{ fmt(memStat.avg, '%') }}</span>
          <span>峰值: {{ fmt(memStat.max, '%') }}</span>
        </div>
      </div>

      <div class="chart-card span-2">
        <div class="chart-card-title">网络流量 (KB/s)</div>
        <div v-if="!hasData" class="chart-empty">暂无数据</div>
        <LineChart v-else :series="netSeries" :timestamps="gridData.timestamps" :height="200" unit=" KB/s" />
      </div>
    </div>

    <!-- 磁盘状态（仅实时监控 Tab 展示） -->
    <div class="table-card" v-if="activeTab === 'metrics'">
      <div class="table-toolbar">
        <h3>磁盘监控（最新采集值）</h3>
        <span class="dim-text">数据来源：Agent 定时上报 · 每 15 秒自动刷新</span>
      </div>
      <table>
        <thead>
          <tr>
            <th>挂载点</th>
            <th>文件系统</th>
            <th>空间使用率</th>
            <th>已用 / 总空间</th>
            <th>读取吞吐</th>
            <th>写入吞吐</th>
            <th>IOPS（读/写）</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(d, i) in disks" :key="i">
            <td><strong class="mono">{{ d.mountPoint }}</strong></td>
            <td>{{ d.fsType || '-' }}</td>
            <td>
              <div class="progress-wrap">
                <div class="progress-bar">
                  <div
                    class="progress-inner"
                    :class="d.diskUsage >= 90 ? 'danger' : d.diskUsage >= 70 ? 'warning' : 'success'"
                    :style="{ width: Math.min(d.diskUsage || 0, 100) + '%' }"
                  ></div>
                </div>
                <span class="progress-text">{{ fmt(d.diskUsage, '%') }}</span>
              </div>
            </td>
            <td>{{ fmt(d.diskUsedGb, 'GB') }} / {{ fmt(d.diskTotalGb, 'GB') }}</td>
            <td>{{ fmt(d.diskReadKbps, ' KB/s') }}</td>
            <td>{{ fmt(d.diskWriteKbps, ' KB/s') }}</td>
            <td>{{ d.diskIopsRead ?? '-' }} / {{ d.diskIopsWrite ?? '-' }}</td>
          </tr>
          <tr v-if="!disks.length">
            <td colspan="7" class="empty-cell">暂无磁盘监控数据</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 历史告警：复用告警管理模块接口，按当前服务器 serverCode 筛选 -->
    <div class="table-card" v-if="activeTab === 'alerts'">
      <div class="table-toolbar">
        <h3>历史告警</h3>
        <span class="dim-text">共 {{ alertTotal }} 条</span>
        <button class="btn btn-default" :disabled="alertLoading" @click="loadAlertHistory(alertPage)">
          刷新
        </button>
      </div>

      <!-- 加载态 -->
      <div v-if="alertLoading" class="alert-loading">加载中...</div>

      <!-- 空态 -->
      <div v-else-if="!alertList.length" class="alert-empty">该服务器暂无历史告警</div>

      <!-- 列表 -->
      <table v-else>
        <thead>
          <tr>
            <th style="width: 90px">级别</th>
            <th>告警内容</th>
            <th style="width: 100px">状态</th>
            <th style="width: 170px">触发时间</th>
            <th style="width: 90px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in alertList" :key="row.id">
            <td>
              <span class="badge" :class="'badge-' + alertMap(ALERT_LEVEL_MAP, row.alertLevel).cls">
                {{ alertMap(ALERT_LEVEL_MAP, row.alertLevel).label }}
              </span>
            </td>
            <td>
              <div class="alert-content">{{ row.alertContent || '-' }}</div>
              <div v-if="row.alertRuleName" class="alert-rule">{{ row.alertRuleName }}</div>
            </td>
            <td>
              <span class="badge" :class="'badge-' + alertMap(ALERT_STATUS_MAP, row.alertStatus).cls">
                {{ alertMap(ALERT_STATUS_MAP, row.alertStatus).label }}
              </span>
            </td>
            <td class="mono">{{ row.triggerTime || row.createdAt || '-' }}</td>
            <td>
              <a class="link" @click="router.push('/alerts/detail/' + row.id)">详情</a>
            </td>
          </tr>
        </tbody>
      </table>

      <!-- 分页 -->
      <div v-if="alertTotal > alertPageSize" class="pager">
        <button class="btn btn-default" :disabled="alertPage <= 1 || alertLoading" @click="loadAlertHistory(alertPage - 1)">上一页</button>
        <span class="pager-info">第 {{ alertPage }} 页 / 共 {{ Math.ceil(alertTotal / alertPageSize) }} 页</span>
        <button class="btn btn-default" :disabled="alertPage * alertPageSize >= alertTotal || alertLoading" @click="loadAlertHistory(alertPage + 1)">下一页</button>
      </div>
    </div>

    <!-- 自愈记录：复用自愈日志模块接口，按当前服务器 serverCode 筛选 -->
    <div class="table-card" v-if="activeTab === 'healings'">
      <div class="table-toolbar">
        <h3>自愈记录</h3>
        <span class="dim-text">共 {{ healingTotal }} 条</span>
        <button class="btn btn-default" :disabled="healingLoading" @click="loadHealingHistory(healingPage)">
          刷新
        </button>
      </div>

      <!-- 加载态 -->
      <div v-if="healingLoading" class="alert-loading">加载中...</div>

      <!-- 空态 -->
      <div v-else-if="!healingList.length" class="alert-empty">该服务器暂无自愈记录</div>

      <!-- 列表 -->
      <table v-else>
        <thead>
          <tr>
            <th>时间</th>
            <th style="width: 140px">触发规则</th>
            <th>触发原因</th>
            <th>执行动作</th>
            <th style="width: 90px">结果</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in healingList" :key="row.id">
            <td class="mono">{{ healTime(row.startedAt) }}</td>
            <td>{{ row.ruleName || '-' }}</td>
            <td class="heal-detail" :title="healTriggerDetail(row.triggerDetail)">{{ healTriggerDetail(row.triggerDetail) }}</td>
            <td class="heal-cmd" :title="row.executeCommand">{{ row.executeCommand || '-' }}</td>
            <td>
              <span class="badge" :class="'badge-' + alertMap(HEALING_STATUS_MAP, row.executionStatus).cls">
                {{ alertMap(HEALING_STATUS_MAP, row.executionStatus).label }}
              </span>
            </td>
          </tr>
        </tbody>
      </table>

      <!-- 分页 -->
      <div v-if="healingTotal > healingPageSize" class="pager">
        <button class="btn btn-default" :disabled="healingPage <= 1 || healingLoading" @click="loadHealingHistory(healingPage - 1)">上一页</button>
        <span class="pager-info">第 {{ healingPage }} 页 / 共 {{ Math.ceil(healingTotal / healingPageSize) }} 页</span>
        <button class="btn btn-default" :disabled="healingPage * healingPageSize >= healingTotal || healingLoading" @click="loadHealingHistory(healingPage + 1)">下一页</button>
      </div>
    </div>

    <!-- 服务列表：复用服务监控模块快照接口，按当前服务器 serverCode 筛选 -->
    <div class="table-card" v-if="activeTab === 'services'">
      <div class="table-toolbar">
        <h3>服务列表</h3>
        <span class="dim-text">共 {{ serviceTotal }} 项</span>
        <button class="btn btn-default" :disabled="serviceLoading" @click="loadServices(servicePage)">
          刷新
        </button>
      </div>

      <!-- 加载态 -->
      <div v-if="serviceLoading" class="alert-loading">加载中...</div>

      <!-- 空态 -->
      <div v-else-if="!serviceList.length" class="alert-empty">该服务器暂无监控服务</div>

      <!-- 列表 -->
      <table v-else>
        <thead>
          <tr>
            <th>服务名称</th>
            <th style="width: 100px">端口状态</th>
            <th style="width: 90px">PID</th>
            <th style="width: 120px">状态</th>
            <th style="width: 170px">最近检测</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="s in serviceList" :key="s.id">
            <td><strong>{{ s.serviceName || '-' }}</strong></td>
            <td><span class="tag" :class="servicePortInfo(s).tag">{{ servicePortInfo(s).text }}</span></td>
            <td class="mono">{{ s.pid || '-' }}</td>
            <td>
              <span class="status-dot" :class="serviceStatusInfo(s).dot"></span>
              <span class="tag" :class="serviceStatusInfo(s).tag">{{ serviceStatusInfo(s).text }}</span>
            </td>
            <td class="mono">{{ fmtCheckTime(s.lastCheckTime) }}</td>
          </tr>
        </tbody>
      </table>

      <!-- 分页 -->
      <div v-if="serviceTotal > servicePageSize" class="pager">
        <button class="btn btn-default" :disabled="servicePage <= 1 || serviceLoading" @click="loadServices(servicePage - 1)">上一页</button>
        <span class="pager-info">第 {{ servicePage }} 页 / 共 {{ Math.ceil(serviceTotal / servicePageSize) }} 页</span>
        <button class="btn btn-default" :disabled="servicePage * servicePageSize >= serviceTotal || serviceLoading" @click="loadServices(servicePage + 1)">下一页</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.back-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: #409eff;
  margin-bottom: 12px;
  cursor: pointer;
}
.back-link:hover { text-decoration: underline; }
.back-link svg { width: 14px; height: 14px; fill: #409eff; }

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
.btn-primary { background: #409eff; border-color: #409eff; color: #fff; }
.btn-primary:hover:not(:disabled) { background: #66b1ff; border-color: #66b1ff; }
.btn-default { background: var(--card-bg); border-color: var(--input-border); color: var(--text-regular); }
.btn-default:hover:not(:disabled) { color: #409eff; border-color: #c6e2ff; background: #ecf5ff; }
.btn-primary:disabled,
.btn-default:disabled { opacity: 0.6; cursor: not-allowed; }

.info-card {
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: 10px;
  padding: 24px;
  margin-bottom: 20px;
}
.info-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
  flex-wrap: wrap;
  gap: 10px;
}
.info-title {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.info-title h2 { font-size: 20px; font-weight: 600; color: var(--text-primary); }
.info-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 20px;
}
.info-item label {
  display: block;
  font-size: 12px;
  color: var(--text-secondary);
  margin-bottom: 4px;
}
.info-item .value {
  font-size: 14px;
  color: var(--text-primary);
  font-weight: 500;
  word-break: break-all;
}

.tag {
  display: inline-flex;
  align-items: center;
  padding: 3px 10px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
}
.tag-success { background: #f0f9eb; color: #67c23a; border: 1px solid #e1f3d8; }
.tag-danger { background: #fef0f0; color: #f56c6c; border: 1px solid #fde2e2; }
.tag-info { background: #ecf5ff; color: #409eff; border: 1px solid #d9ecff; }

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.status-dot.green { background: #67c23a; box-shadow: 0 0 4px rgba(103, 194, 58, 0.5); }
.status-dot.red { background: #f56c6c; box-shadow: 0 0 4px rgba(245, 108, 108, 0.5); }

.tabs {
  display: flex;
  border-bottom: 2px solid var(--card-border);
  margin-bottom: 16px;
}
.tab {
  padding: 10px 20px;
  font-size: 14px;
  color: var(--text-regular);
  cursor: pointer;
  border-bottom: 2px solid transparent;
  margin-bottom: -2px;
  transition: all 0.2s;
}
.tab.active { color: #409eff; border-bottom-color: #409eff; font-weight: 500; }
.tab.disabled { color: var(--text-disabled); cursor: not-allowed; }

.range-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
}
.range-label { font-size: 13px; color: var(--text-secondary); }
.chart-btn {
  padding: 5px 14px;
  background: var(--card-bg);
  border: 1px solid var(--input-border);
  border-radius: 6px;
  color: var(--text-regular);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
  font-family: inherit;
}
.chart-btn:hover { border-color: #409eff; color: #409eff; }
.chart-btn.active {
  background: #ecf5ff;
  border-color: #409eff;
  color: #409eff;
}
.loading-tip { font-size: 12px; color: #409eff; }

.ws-status { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; margin-left: 10px; color: #909399; }
.ws-dot { width: 8px; height: 8px; border-radius: 50%; background: #c0c4cc; display: inline-block; }
.ws-open { color: #67c23a; }
.ws-open .ws-dot { background: #67c23a; box-shadow: 0 0 0 3px rgba(103,194,58,0.18); }
.ws-connecting, .ws-reconnecting { color: #e6a23c; }
.ws-connecting .ws-dot, .ws-reconnecting .ws-dot { background: #e6a23c; animation: ws-blink 1s infinite; }
.ws-closed { color: #f56c6c; }
.ws-closed .ws-dot { background: #f56c6c; }
@keyframes ws-blink { 0%,100% { opacity: 1; } 50% { opacity: 0.3; } }

.charts-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
  margin-bottom: 20px;
}
.span-2 { grid-column: span 2; }
.chart-card {
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: 10px;
  padding: 20px;
}
.chart-card-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 16px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.chart-card-title::before {
  content: '';
  width: 3px;
  height: 14px;
  background: #409eff;
  border-radius: 2px;
}
.chart-empty {
  height: 180px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-secondary);
  font-size: 13px;
  background: var(--row-hover-bg);
  border-radius: 8px;
}
.chart-footer {
  display: flex;
  justify-content: space-between;
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-secondary);
}

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
.table-toolbar h3 { font-size: 14px; font-weight: 600; color: var(--text-primary); }
.dim-text { font-size: 12px; color: var(--text-disabled); }

table { width: 100%; border-collapse: collapse; }
thead th {
  text-align: left;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  padding: 10px 16px;
  background: var(--row-hover-bg);
  border-bottom: 1px solid var(--card-border);
  white-space: nowrap;
}
tbody td {
  padding: 12px 16px;
  font-size: 13px;
  color: var(--text-primary);
  border-bottom: 1px solid var(--card-border);
  white-space: nowrap;
}
tbody tr:hover { background: var(--row-hover-bg); }
tbody tr:last-child td { border-bottom: none; }
.empty-cell { text-align: center; color: var(--text-secondary); padding: 40px !important; white-space: normal !important; }

.mono { font-family: Consolas, Monaco, monospace; }

.progress-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 160px;
}
.progress-bar {
  flex: 1;
  height: 8px;
  background: var(--row-hover-bg);
  border-radius: 4px;
  overflow: hidden;
}
.progress-inner {
  height: 100%;
  border-radius: 4px;
  transition: width 0.4s;
}
.progress-inner.success { background: linear-gradient(90deg, #67c23a, #95de64); }
.progress-inner.warning { background: linear-gradient(90deg, #e6a23c, #ffc069); }
.progress-inner.danger { background: linear-gradient(90deg, #f56c6c, #ff9e9e); }
.progress-text { font-size: 12px; color: var(--text-regular); min-width: 44px; }

@media (max-width: 1100px) {
  .info-grid { grid-template-columns: repeat(2, 1fr); }
  .charts-row { grid-template-columns: 1fr; }
  .span-2 { grid-column: span 1; }
}

/* ===== 历史告警区块样式（徽标/链接/分页与告警模块保持一致） ===== */
.alert-loading,
.alert-empty {
  padding: 32px 0;
  text-align: center;
  color: var(--text-disabled);
  font-size: 13px;
}
.badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 12px;
  line-height: 1.6;
  white-space: nowrap;
}
.badge-info { background: #ecf5ff; color: #409eff; }
.badge-warning { background: #fdf6ec; color: #e6a23c; }
.badge-danger { background: #fef0f0; color: #f56c6c; }
.badge-gray { background: #f4f4f5; color: #909399; }
.badge-success { background: #f0f9eb; color: #67c23a; }
.alert-content {
  max-width: 520px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.alert-rule { font-size: 12px; color: var(--text-disabled); margin-top: 2px; }
/* 自愈记录：触发原因 / 执行命令 超长省略 */
.heal-detail {
  max-width: 360px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.heal-cmd {
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  color: var(--text-regular);
}
.link {
  color: #409eff;
  cursor: pointer;
  font-size: 13px;
}
.link:hover { text-decoration: underline; }
.pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 14px;
}
.pager-info { font-size: 12px; color: var(--text-regular); }

/* 详情加载失败提示区：停留页面 + 提供重试 */
.detail-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 12px 0;
  padding: 12px 16px;
  border: 1px solid var(--el-color-danger, #f56c6c);
  background: var(--el-color-danger-light-9, #fef0f0);
  color: var(--el-color-danger, #f56c6c);
  border-radius: 8px;
  font-size: 13px;
}
</style>
