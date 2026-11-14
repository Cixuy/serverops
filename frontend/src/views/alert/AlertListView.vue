<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getAlertEvents, alertAction, createAlertEvent } from '@/api/alert'
import { getServerList, getMonitorConfigList, getHealingRules } from '@/api'
// getHealingRules：自愈规则列表，用于上报弹窗中关联 rule_id（接口文档新增字段）
import { ElMessage } from '@/utils/message'
import AppPagination from '@/components/common/AppPagination.vue'
import AppSpinner from '@/components/common/AppSpinner.vue'

// 路由实例：点击服务器编码跳转到告警详情页（/alerts/detail/:id）
const router = useRouter()

/*
 * ============================================================
 * 告警列表页（以后端实际实现为准，接口文档差异已在此对齐）
 * - 后端列表字段为 alertStatus / triggerTime（非文档的 status），
 *   autoHealStatus / healingRuleId 后端暂未返回，故不展示独立自愈
 *   状态列；状态列已含 HEALING（自愈中），待后端补字段后再考虑加回。
 * - 后端筛选支持：serverCode / alertLevel / alertStatus /
 *   startTime / endTime（ISO date-time），无关键词搜索。
 * - 状态枚举：PENDING / HEALING / RESOLVED / IGNORED。
 * - alertContent 为后端解析后的 JSON 对象，展示前需序列化。
 * ============================================================
 */

/* ===== 枚举与展示映射 ===== */
// 告警级别（接口字段 alertLevel）
const LEVEL_MAP = {
  CRITICAL: { label: '严重', cls: 'danger' },
  WARNING: { label: '警告', cls: 'warning' },
  INFO: { label: '信息', cls: 'info' },
}
// 告警处理状态（接口字段 alertStatus，后端枚举：PENDING/HEALING/RESOLVED/IGNORED）
const STATUS_MAP = {
  PENDING: { label: '未处理', cls: 'warning' },
  HEALING: { label: '自愈中', cls: 'info' },
  RESOLVED: { label: '已处理', cls: 'success' },
  IGNORED: { label: '已忽略', cls: 'gray' },
}
// 处理记录动作类型（接口字段 actionType；后端另支持 SYSTEM_RESOLVE）
const ACTION_MAP = {
  AUTO_HEAL: { label: '自动自愈', cls: 'warning' },
  MANUAL_RECOVER: { label: '手动恢复', cls: 'success' },
  IGNORE: { label: '忽略', cls: 'gray' },
  SYSTEM_RESOLVE: { label: '系统恢复', cls: 'info' },
}

function mapInfo(map, value) {
  // 未知枚举兜底：直接展示原始值，避免页面空白
  return map[value] || { label: value || '-', cls: 'gray' }
}

/* ===== 列表状态 ===== */
const loading = ref(false)
const list = ref([])
const total = ref(0)
const query = reactive({
  pageNum: 1,
  pageSize: 10,
  serverCode: '', // 后端按服务器编码筛选（无关键词搜索）
  alertLevel: '',
  alertStatus: '', // 后端字段名为 alertStatus
  startDate: '',
  endDate: '',
})
const quick = reactive({ filter: 'all' })

// 快速筛选标签：all=全部；critical/warning/info 按级别；pending/resolved/ignored 按状态
const quickTags = [
  { key: 'all', label: '全部', color: '' },
  { key: 'critical', label: '严重', color: '#F56C6C' },
  { key: 'warning', label: '警告', color: '#E6A23C' },
  { key: 'info', label: '信息', color: '#409EFF' },
  { key: 'pending', label: '未处理', color: '' },
  { key: 'resolved', label: '已处理', color: '' },
  { key: 'ignored', label: '已忽略', color: '' },
]
// 各标签计数：全部由主列表 total 填充，其余通过 pageSize=1 轻量查询统计
const stats = reactive({
  all: 0, critical: 0, warning: 0, info: 0, pending: 0, resolved: 0, ignored: 0,
})

const MIN_DELAY = 500
function withMinDelay(promise) {
  return Promise.all([promise, new Promise((r) => setTimeout(r, MIN_DELAY))]).then(
    ([data]) => data
  )
}

// 判断当前是否为无筛选状态（用于决定是否覆盖“全部”标签计数）
function isPlainQuery() {
  return (
    query.alertLevel === '' &&
    query.alertStatus === '' &&
    !query.serverCode.trim() &&
    !query.startDate &&
    !query.endDate
  )
}

async function fetchList() {
  if (loading.value) return
  loading.value = true
  try {
    const params = { pageNum: query.pageNum, pageSize: query.pageSize }
    // 后端筛选参数：serverCode / alertLevel / alertStatus / startTime / endTime
    if (query.serverCode.trim()) params.serverCode = query.serverCode.trim()
    if (query.alertLevel) params.alertLevel = query.alertLevel
    if (query.alertStatus) params.alertStatus = query.alertStatus
    // 时间转 ISO date-time（后端要求 ISO.DATE_TIME，如 2025-01-15T00:00:00）
    if (query.startDate) params.startTime = `${query.startDate}T00:00:00`
    if (query.endDate) params.endTime = `${query.endDate}T23:59:59`
    const data = await withMinDelay(getAlertEvents(params))
    list.value = data?.list || []
    total.value = data?.total || 0
    // 无筛选时刷新“全部”标签计数，其余标签计数由 fetchStats 维护
    if (isPlainQuery()) stats.all = total.value
  } finally {
    loading.value = false
  }
}

async function fetchStats() {
  // 并行发起 pageSize=1 的轻量查询，仅取 total 作为快速标签计数
  const jobs = [
    { key: 'critical', params: { alertLevel: 'CRITICAL' } },
    { key: 'warning', params: { alertLevel: 'WARNING' } },
    { key: 'info', params: { alertLevel: 'INFO' } },
    { key: 'pending', params: { alertStatus: 'PENDING' } },
    { key: 'resolved', params: { alertStatus: 'RESOLVED' } },
    { key: 'ignored', params: { alertStatus: 'IGNORED' } },
  ]
  const results = await Promise.allSettled(
    jobs.map((j) => getAlertEvents({ pageNum: 1, pageSize: 1, ...j.params }))
  )
  jobs.forEach((j, i) => {
    if (results[i].status === 'fulfilled') {
      stats[j.key] = results[i].value?.total || 0
    }
  })
}

/* ===== 筛选 ===== */
// 时间范围校验：开始日期不能晚于结束日期（date 输入为 YYYY-MM-DD，可直接字符串比较）
const timeError = ref('')

// 校验时间区间，返回是否合法；非法时设置错误提示
function validateTimeRange() {
  if (query.startDate && query.endDate && query.startDate > query.endDate) {
    timeError.value = '开始时间不能晚于结束时间'
    return false
  }
  timeError.value = ''
  return true
}

function handleSearch() {
  // 校验时间区间合法性，不合法则中止查询
  if (!validateTimeRange()) return
  query.pageNum = 1
  fetchList()
}

function handleReset() {
  Object.assign(query, {
    serverCode: '', alertLevel: '', alertStatus: '', startDate: '', endDate: '', pageNum: 1,
  })
  timeError.value = ''
  quick.filter = 'all'
  fetchList()
}

function handlePageChange(p) {
  query.pageNum = p
  fetchList()
}

// 每页条数变更：重置回第 1 页再查询，防止停留在超出新分页范围的页码
function handleSizeChange(size) {
  if (size === query.pageSize) return
  query.pageSize = size
  query.pageNum = 1
  fetchList()
}

// 点击快速筛选标签：映射为对应级别/状态条件后查询
function applyQuick(tag) {
  quick.filter = tag.key
  query.pageNum = 1
  query.alertLevel = ''
  query.alertStatus = ''
  if (tag.key === 'critical') query.alertLevel = 'CRITICAL'
  else if (tag.key === 'warning') query.alertLevel = 'WARNING'
  else if (tag.key === 'info') query.alertLevel = 'INFO'
  else if (tag.key === 'pending') query.alertStatus = 'PENDING'
  else if (tag.key === 'resolved') query.alertStatus = 'RESOLVED'
  else if (tag.key === 'ignored') query.alertStatus = 'IGNORED'
  fetchList()
}

// 非激活状态的彩色标签用内联样式着色，激活态交给 CSS 高亮
function tagStyle(tag) {
  if (quick.filter === tag.key || !tag.color) return {}
  return { borderColor: tag.color, color: tag.color }
}

/* ===== 行操作 ===== */
const actingId = ref(null)

// 对单个告警执行处理动作（AUTO_HEAL / MANUAL_RECOVER / IGNORE）
async function handleAction(row, actionType) {
  actingId.value = row.id
  try {
    await alertAction(row.id, { actionType })
    const tip = {
      AUTO_HEAL: '已触发自愈，可在详情处理记录中查看结果',
      MANUAL_RECOVER: '告警已标记为已处理',
      IGNORE: '告警已忽略',
    }[actionType]
    ElMessage.success(tip)
    fetchList()
  } catch {
    /* 拦截器已提示 */
  } finally {
    actingId.value = null
  }
}

/* ===== 批量操作 ===== */
const selected = ref(new Set())
const batchActing = ref(false)

// 已关闭的告警（已处理 RESOLVED / 已忽略 IGNORED）不允许再操作；
// 只有未处理 PENDING 和自愈中 HEALING 才可触发自愈/标记处理/忽略
function canOperate(a) {
  return a.alertStatus === 'PENDING' || a.alertStatus === 'HEALING'
}

// 当前页可操作的行：全选与批量操作都只针对这些行，避免把已关闭告警提交给后端
const operableList = computed(() => list.value.filter(canOperate))

const allChecked = computed(
  () =>
    operableList.value.length > 0 &&
    operableList.value.every((a) => selected.value.has(a.id))
)
const selectedCount = computed(() => selected.value.size)

function toggleRow(id) {
  const next = new Set(selected.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  selected.value = next
}

function toggleAll(e) {
  // 全选时只勾选可操作的行（已关闭行由复选框 disabled 兜底）
  const next = new Set()
  if (e.target.checked) operableList.value.forEach((a) => next.add(a.id))
  selected.value = next
}

async function batchAction(actionType) {
  // 未勾选任何告警时给出提示，避免按钮禁用让用户误以为不可用
  if (!selected.value.size) {
    ElMessage.warning('请先勾选需要操作的告警')
    return
  }
  if (batchActing.value) return
  batchActing.value = true
  try {
    // 防御性过滤：勾选中若混入已关闭（RESOLVED/IGNORED）的告警，后端会返回
    // 409「不可重复操作」；这里只提交可操作状态（PENDING/HEALING）的记录
    const ids = [...selected.value].filter((id) => {
      const row = list.value.find((a) => a.id === id)
      return row && canOperate(row)
    })
    if (!ids.length) {
      ElMessage.warning('选中的告警均已处理或已忽略，无需操作')
      return
    }
    // 并行提交批量动作，单个失败不阻塞其余
    const results = await Promise.allSettled(
      ids.map((id) => alertAction(id, { actionType }))
    )
    const ok = results.filter((r) => r.status === 'fulfilled').length
    if (ok > 0) {
      const verb = actionType === 'MANUAL_RECOVER' ? '标记已处理' : '忽略'
      // 若存在被过滤掉的已关闭记录，在提示中说明被跳过
      const skipped = ids.length - ok
      const closed = selected.value.size - ids.length
      const extra = [closed ? `跳过已关闭 ${closed} 条` : '', skipped ? `${skipped} 条失败` : ''].filter(Boolean).join('，')
      ElMessage.success(`已${verb} ${ok} 条告警${extra ? `（${extra}）` : ''}`)
    } else {
      ElMessage.error('批量操作失败，请稍后重试')
    }
    selected.value = new Set()
    fetchList()
  } finally {
    batchActing.value = false
  }
}

/* ===== 详情跳转 ===== */
// 跳转到独立告警详情页；无 id 时交给详情页兜底加载最新告警
function goDetail(id) {
  if (!id) return
  router.push(`/alerts/detail/${id}`)
}

/* ===== 格式化 ===== */
function fmtTime(dt) {
  if (!dt) return '-'
  const d = new Date(dt)
  if (isNaN(d.getTime())) return String(dt)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

// alertContent 为后端解析后的 JSON 对象，统一序列化后再展示
function fmtContent(content) {
  if (content == null) return '-'
  if (typeof content === 'string') return content
  try {
    return JSON.stringify(content)
  } catch {
    return String(content)
  }
}

// 未处理行高亮（原型中 unread 样式）
function isPending(a) {
  return a.alertStatus === 'PENDING'
}

/* ===== 告警上报（模拟客户端上报告警事件）===== */
// 上报弹窗与表单：用于向服务端 POST /alert-events 落库告警事件
// 字段对齐接口文档 AlertEventCreateRequest（server_code/config_id 等必填）
const reportVisible = ref(false)
const reporting = ref(false)
const reportForm = reactive({
  serverCode: '', // 服务器唯一编码
  configId: undefined, // 关联的监控服务配置 ID
  serviceName: '', // 告警服务名称
  alertType: 'SERVICE_DOWN', // 告警类型枚举
  alertLevel: 'CRITICAL', // 告警级别
  alertContent: '', // 结构化异常上下文（JSON 字符串）
  triggerTime: '', // 触发时间
  autoHealStatus: '', // 自愈结果状态（可选）
  resolveTime: '', // 自愈恢复时间（可选）
  ruleId: undefined, // 关联的自愈规则 ID（可选，接口文档 AlertEventCreateRequest 新增字段）
})
const reportErrors = reactive({})
// 上报所需的下拉数据：服务器列表（code）、监控配置（id/serviceName）与自愈规则（id/ruleName）
const serverOptions = ref([])
const configOptions = ref([])
const ruleOptions = ref([])

// 告警类型与自愈状态枚举（与接口文档一致）
const ALERT_TYPE_OPTIONS = ['SERVICE_DOWN', 'SERVICE_DEGRADED', 'CONFIG_ERROR']
const AUTO_HEAL_STATUS_OPTIONS = ['SUCCESS', 'FAILED', 'SKIPPED']

// 打开上报弹窗：拉取服务器与监控配置选项，并填充默认触发时间为当前时间
async function openReport() {
  Object.assign(reportForm, {
    serverCode: '',
    configId: undefined,
    serviceName: '',
    alertType: 'SERVICE_DOWN',
    alertLevel: 'CRITICAL',
    alertContent: '',
    triggerTime: '',
    autoHealStatus: '',
    resolveTime: '',
    ruleId: undefined,
  })
  Object.keys(reportErrors).forEach((k) => delete reportErrors[k])
  reportVisible.value = true
  // 并行拉取下拉数据，供选择服务器编码、关联监控配置与自愈规则
  try {
    const [serverRes, configRes, ruleRes] = await Promise.allSettled([
      getServerList({ pageNum: 1, pageSize: 100 }),
      getMonitorConfigList({ pageNum: 1, pageSize: 100 }),
      getHealingRules({ pageNum: 1, pageSize: 100 }),
    ])
    serverOptions.value = serverRes.status === 'fulfilled' ? (serverRes.value?.list || []) : []
    configOptions.value = configRes.status === 'fulfilled' ? (configRes.value?.list || []) : []
    ruleOptions.value = ruleRes.status === 'fulfilled' ? (ruleRes.value?.list || []) : []
  } catch {
    serverOptions.value = []
    configOptions.value = []
    ruleOptions.value = []
  }
}

// 选择监控配置时，自动联动填充 serviceName（若手动填过则不覆盖）
function onConfigChange() {
  const cfg = configOptions.value.find((c) => c.id === reportForm.configId)
  if (cfg) reportForm.serviceName = cfg.serviceName || reportForm.serviceName
}

// 校验上报表单必填项，返回是否通过
function validateReport() {
  Object.keys(reportErrors).forEach((k) => delete reportErrors[k])
  if (!reportForm.serverCode.trim()) reportErrors.serverCode = '请选择服务器'
  if (!reportForm.configId) reportErrors.configId = '请选择监控配置'
  if (!reportForm.serviceName.trim()) reportErrors.serviceName = '请输入服务名称'
  if (!reportForm.triggerTime) reportErrors.triggerTime = '请选择触发时间'
  // alertContent 为可选 JSON，若填写则校验格式
  if (reportForm.alertContent.trim()) {
    try {
      JSON.parse(reportForm.alertContent)
    } catch {
      reportErrors.alertContent = '异常上下文必须是合法 JSON 字符串'
    }
  }
  return Object.keys(reportErrors).length === 0
}

// 将 datetime-local 值（如 2026-08-24T10:30）补齐为后端要求的 ISO date-time（YYYY-MM-DDTHH:mm:ss）
function toIsoDateTime(local) {
  if (!local) return undefined
  // 已含秒位则原样返回，否则补 ":00"
  return /:\d{2}$/.test(local) ? local : `${local}:00`
}

// 提交上报：组装接口所需字段（可选字段为空则不传），调用成功后刷新列表
async function handleReport() {
  if (!validateReport()) return
  reporting.value = true
  try {
    const payload = {
      server_code: reportForm.serverCode.trim(),
      config_id: reportForm.configId,
      service_name: reportForm.serviceName.trim(),
      alert_type: reportForm.alertType,
      alert_level: reportForm.alertLevel,
      trigger_time: toIsoDateTime(reportForm.triggerTime),
    }
    // 可选字段：异常上下文（JSON 字符串转对象）、自愈结果、恢复时间、关联规则
    if (reportForm.alertContent.trim()) payload.alert_content = JSON.parse(reportForm.alertContent)
    if (reportForm.autoHealStatus) payload.auto_heal_status = reportForm.autoHealStatus
    if (reportForm.resolveTime) payload.resolve_time = toIsoDateTime(reportForm.resolveTime)
    if (reportForm.ruleId) payload.rule_id = reportForm.ruleId // 接口文档新增：关联自愈规则ID（可选）
    const res = await createAlertEvent(payload)
    ElMessage.success(`告警上报成功（编号：${res?.alertNo || '-'}）`)
    reportVisible.value = false
    fetchList()
  } catch {
    /* 拦截器已提示 */
  } finally {
    reporting.value = false
  }
}

onMounted(() => {
  fetchList()
  fetchStats()
})
</script>

<template>
  <div class="alert-list">
    <div class="page-header">
      <div class="page-title">告警列表</div>
      <div class="page-actions">
        <!-- 上报告警：模拟客户端向服务端上报一条告警事件（POST /alert-events） -->
        <button class="btn btn-primary" @click="openReport">
          <svg viewBox="0 0 24 24"><path d="M12 22a2.5 2.5 0 002-4H10a2.5 2.5 0 002 4zm6-6V11a6 6 0 10-12 0v5l-2 2v1h16v-1l-2-2z" /></svg>
          上报告警
        </button>
        <button class="btn btn-default" :disabled="loading" @click="fetchList">
          <AppSpinner v-if="loading" :size="14" />
          <svg v-else viewBox="0 0 24 24"><path d="M17.65 6.35A7.958 7.958 0 0012 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0112 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z" /></svg>
          {{ loading ? '刷新中...' : '刷新' }}
        </button>
      </div>
    </div>

    <!-- 快速筛选标签 -->
    <div class="quick-filters">
      <div
        v-for="tag in quickTags"
        :key="tag.key"
        class="quick-tag"
        :class="{ active: quick.filter === tag.key }"
        :style="tagStyle(tag)"
        @click="applyQuick(tag)"
      >
        {{ tag.label }}
        <span class="count">{{ stats[tag.key] }}</span>
      </div>
    </div>

    <!-- 筛选栏 -->
    <div class="filter-bar">
      <div class="filter-item">
        <span class="filter-label">服务器</span>
        <input
          v-model="query.serverCode"
          class="filter-input"
          placeholder="按服务器编码筛选，如 SRV-008"
          @keyup.enter="handleSearch"
        />
      </div>
      <div class="filter-item">
        <span class="filter-label">级别</span>
        <select v-model="query.alertLevel" class="filter-select" @change="handleSearch">
          <option value="">全部</option>
          <option value="CRITICAL">严重 CRITICAL</option>
          <option value="WARNING">警告 WARNING</option>
          <option value="INFO">信息 INFO</option>
        </select>
      </div>
      <div class="filter-item">
        <span class="filter-label">状态</span>
        <select v-model="query.alertStatus" class="filter-select" @change="handleSearch">
          <option value="">全部</option>
          <option value="PENDING">未处理</option>
          <option value="HEALING">自愈中</option>
          <option value="RESOLVED">已处理</option>
          <option value="IGNORED">已忽略</option>
        </select>
      </div>
      <div class="filter-item">
        <span class="filter-label">时间</span>
        <!-- max/min 由浏览器原生限制：开始时间不能晚于结束时间，结束时间不能早于开始时间 -->
        <input
          v-model="query.startDate"
          type="date"
          class="filter-select date-input"
          :max="query.endDate || undefined"
          @change="validateTimeRange"
        />
        <span class="date-sep">至</span>
        <input
          v-model="query.endDate"
          type="date"
          class="filter-select date-input"
          :min="query.startDate || undefined"
          @change="validateTimeRange"
        />
        <p v-if="timeError" class="filter-error">{{ timeError }}</p>
      </div>
      <button class="btn btn-primary btn-sm" :disabled="loading" @click="handleSearch">
        <AppSpinner v-if="loading" variant="white" :size="14" />
        {{ loading ? '查询中...' : '筛选' }}
      </button>
      <button class="btn btn-default btn-sm" :disabled="loading" @click="handleReset">重置</button>
    </div>

    <!-- 表格 -->
    <div class="table-card">
      <div class="table-toolbar">
        <h3>告警记录（{{ total }} 条）</h3>
        <div class="toolbar-actions">
          <span v-if="selectedCount" class="select-tip">已选 {{ selectedCount }} 条</span>
          <button
            class="btn btn-default btn-sm"
            :disabled="batchActing"
            @click="batchAction('MANUAL_RECOVER')"
          >
            <AppSpinner v-if="batchActing" :size="14" />
            批量标记已处理
          </button>
          <button
            class="btn btn-default btn-sm"
            :disabled="batchActing"
            @click="batchAction('IGNORE')"
          >
            批量忽略
          </button>
        </div>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th style="width: 35px">
                <input type="checkbox" :checked="allChecked" @change="toggleAll" />
              </th>
              <th>级别</th>
              <th>服务器</th>
              <th>告警内容</th>
              <th>状态</th>
              <th>触发时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="a in list" :key="a.id" :class="{ unread: isPending(a) }">
              <td>
                <!-- 已关闭（RESOLVED/IGNORED）的告警禁止勾选，防止被批量操作误提交 -->
                <input
                  type="checkbox"
                  :checked="selected.has(a.id)"
                  :disabled="!canOperate(a)"
                  :title="canOperate(a) ? '' : '该告警已关闭，不可操作'"
                  @change="toggleRow(a.id)"
                />
              </td>
              <td>
                <span class="level-indicator" :class="'level-' + mapInfo(LEVEL_MAP, a.alertLevel).cls"></span>
                <span class="tag" :class="'tag-' + mapInfo(LEVEL_MAP, a.alertLevel).cls">
                  {{ a.alertLevel }}
                </span>
              </td>
              <td>
                <span class="server-code">{{ a.serverCode || '-' }}</span>
              </td>
              <td>
                <span class="alert-message" :class="{ critical: a.alertLevel === 'CRITICAL' }">
                  {{ fmtContent(a.alertContent) }}
                </span>
              </td>
              <td>
                <span class="tag" :class="'tag-' + mapInfo(STATUS_MAP, a.alertStatus).cls">
                  {{ mapInfo(STATUS_MAP, a.alertStatus).label }}
                </span>
              </td>
              <td class="time">{{ fmtTime(a.triggerTime || a.createdAt) }}</td>
              <td class="ops">
                <button class="btn-text primary" :disabled="actingId === a.id" @click="goDetail(a.id)">详情</button>
                <template v-if="isPending(a)">
                  <button class="btn-text warning" :disabled="actingId === a.id" @click="handleAction(a, 'AUTO_HEAL')">自愈</button>
                  <button class="btn-text success" :disabled="actingId === a.id" @click="handleAction(a, 'MANUAL_RECOVER')">标记处理</button>
                  <button class="btn-text danger" :disabled="actingId === a.id" @click="handleAction(a, 'IGNORE')">忽略</button>
                </template>
                <span v-else-if="a.alertStatus === 'RESOLVED'" class="healed-text">已恢复</span>
                <button
                  v-else-if="a.alertStatus === 'HEALING'"
                  class="btn-text success"
                  :disabled="actingId === a.id"
                  @click="handleAction(a, 'MANUAL_RECOVER')"
                >
                  标记处理
                </button>
                <!-- 已忽略：与已恢复一致只显示静态文本，不允许再标记处理 -->
                <span v-else class="healed-text ignored-text">已忽略</span>
              </td>
            </tr>
            <tr v-if="!list.length && !loading">
              <td colspan="7">
                <div class="empty-state">
                  <svg viewBox="0 0 24 24"><path d="M12 22a2.5 2.5 0 002-4H10a2.5 2.5 0 002 4zm6-6V11a6 6 0 10-12 0v5l-2 2v1h16v-1l-2-2z" /></svg>
                  <p>暂无告警数据</p>
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

    <!-- 告警上报弹窗：模拟客户端向服务端上报告警事件（POST /alert-events） -->
    <!-- 取消点击遮罩关闭：避免填写内容时误点外部导致输入丢失，仅通过 × 或取消按钮关闭 -->
    <div v-if="reportVisible" class="modal-mask">
      <div class="modal-box report-box">
        <div class="modal-header">
          <h3>上报告警事件</h3>
          <button class="modal-close" @click="reportVisible = false">
            <svg viewBox="0 0 24 24"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z" /></svg>
          </button>
        </div>
        <div class="modal-body">
          <div class="form-row">
            <div class="form-item">
              <label>服务器 <span class="required">*</span></label>
              <select v-model="reportForm.serverCode" class="form-input">
                <option value="">请选择服务器</option>
                <option v-for="s in serverOptions" :key="s.id" :value="s.serverCode">
                  {{ s.hostname || s.serverCode }}
                </option>
              </select>
              <div v-if="reportErrors.serverCode" class="error-tip">{{ reportErrors.serverCode }}</div>
            </div>
            <div class="form-item">
              <label>监控配置 <span class="required">*</span></label>
              <select v-model="reportForm.configId" class="form-input" @change="onConfigChange">
                <option :value="undefined">请选择监控服务</option>
                <option v-for="c in configOptions" :key="c.id" :value="c.id">
                  {{ c.serviceName }}（{{ c.serverCode }}）
                </option>
              </select>
              <div v-if="reportErrors.configId" class="error-tip">{{ reportErrors.configId }}</div>
            </div>
          </div>
          <div class="form-row">
            <div class="form-item">
              <label>服务名称 <span class="required">*</span></label>
              <input v-model="reportForm.serviceName" class="form-input" placeholder="如：Nginx" />
              <div v-if="reportErrors.serviceName" class="error-tip">{{ reportErrors.serviceName }}</div>
            </div>
            <div class="form-item">
              <label>告警类型 <span class="required">*</span></label>
              <select v-model="reportForm.alertType" class="form-input">
                <option v-for="t in ALERT_TYPE_OPTIONS" :key="t" :value="t">{{ t }}</option>
              </select>
            </div>
          </div>
          <div class="form-row">
            <div class="form-item">
              <label>告警级别</label>
              <select v-model="reportForm.alertLevel" class="form-input">
                <option value="CRITICAL">严重 CRITICAL</option>
                <option value="WARNING">警告 WARNING</option>
                <option value="INFO">信息 INFO</option>
              </select>
            </div>
            <div class="form-item">
              <label>触发时间 <span class="required">*</span></label>
              <input v-model="reportForm.triggerTime" class="form-input" type="datetime-local" />
              <div v-if="reportErrors.triggerTime" class="error-tip">{{ reportErrors.triggerTime }}</div>
            </div>
          </div>
          <!-- 关联规则（可选）：接口文档 AlertEventCreateRequest 新增 rule_id，用于告警关联自愈规则 -->
          <div class="form-item">
            <label>关联规则</label>
            <select v-model="reportForm.ruleId" class="form-input">
              <option :value="undefined">不关联（可选）</option>
              <option v-for="r in ruleOptions" :key="r.id" :value="r.id">
                {{ r.ruleName }}（#{{ r.id }}）
              </option>
            </select>
          </div>
          <div class="form-row">
            <div class="form-item">
              <label>自愈结果</label>
              <select v-model="reportForm.autoHealStatus" class="form-input">
                <option value="">无（不执行/未自愈）</option>
                <option v-for="st in AUTO_HEAL_STATUS_OPTIONS" :key="st" :value="st">{{ st }}</option>
              </select>
            </div>
            <div class="form-item">
              <label>恢复时间</label>
              <input v-model="reportForm.resolveTime" class="form-input" type="datetime-local" />
            </div>
          </div>
          <div class="form-item">
            <label>异常上下文（JSON，选填）</label>
            <textarea
              v-model="reportForm.alertContent"
              class="form-input report-textarea"
              rows="3"
              placeholder='如：{"error_msg":"Connection refused","pid":1234,"cpu_usage":98.5}'
            ></textarea>
            <div v-if="reportErrors.alertContent" class="error-tip">{{ reportErrors.alertContent }}</div>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-default" @click="reportVisible = false">取消</button>
          <button class="btn btn-primary" :disabled="reporting" @click="handleReport">
            <AppSpinner v-if="reporting" variant="white" :size="14" />
            {{ reporting ? '上报中...' : '提交上报' }}
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
.page-title { font-size: 20px; font-weight: 600; }
.page-actions { display: flex; gap: 10px; }

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
.btn-primary:hover:not(:disabled) { background: #66b1ff; border-color: #66b1ff; }
.btn-default { background: var(--card-bg); border-color: var(--input-border); color: var(--text-regular); }
.btn-default:hover:not(:disabled) { color: #409eff; border-color: #c6e2ff; background: #ecf5ff; }
.btn-primary:disabled { opacity: 0.6; cursor: not-allowed; }
.btn-default:disabled { opacity: 0.6; cursor: not-allowed; }
.btn svg { width: 14px; height: 14px; fill: currentColor; }

/* 快速筛选标签 */
.quick-filters { display: flex; gap: 8px; margin-bottom: 16px; flex-wrap: wrap; }
.quick-tag {
  padding: 6px 14px;
  border-radius: 20px;
  font-size: 12px;
  cursor: pointer;
  border: 1px solid var(--input-border);
  background: var(--card-bg);
  color: var(--text-regular);
  transition: all 0.2s;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-family: inherit;
}
.quick-tag:hover { border-color: #409eff; color: #409eff; }
.quick-tag.active { background: #409eff; color: #fff; border-color: #409eff; }
.quick-tag .count {
  background: rgba(0, 0, 0, 0.08);
  padding: 0 6px;
  border-radius: 10px;
  font-size: 11px;
  line-height: 16px;
}
.quick-tag.active .count { background: rgba(255, 255, 255, 0.25); }

/* 筛选栏 */
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
.filter-item { display: flex; align-items: center; gap: 8px; }
.filter-label { font-size: 13px; color: var(--text-secondary); white-space: nowrap; }
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
.date-input { width: 140px; }
.date-sep { color: var(--text-secondary); font-size: 13px; }
.filter-error { color: #f56c6c; font-size: 12px; margin: 4px 0 0; }

/* 表格 */
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
.toolbar-actions { display: flex; gap: 8px; align-items: center; }
.select-tip { font-size: 12px; color: #409eff; margin-right: 4px; }

/* 表格：容器开启横向滚动，避免多列 nowrap 内容在窄屏下被截断 */
.table-wrap { position: relative; overflow-x: auto; }
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

table {
  width: 100%;
  min-width: 960px; /* 保证各列在窄屏下不被过度挤压，超出部分由横向滚动承载 */
  border-collapse: collapse;
}
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
tbody tr.unread { background: #fdf6ec; }
tbody tr.unread:hover { background: #faecd8; }
td.ops { white-space: nowrap; }

/* 服务器编码链接：主色 + 下划线悬停，点击跳转告警详情页 */
.server-link {
  color: #409eff;
  font-weight: 600;
  cursor: pointer;
  text-decoration: none;
  transition: color 0.2s;
}
.server-link:hover { color: #66b1ff; text-decoration: underline; }

/* 标签 */
.tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 10px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
}
.tag-danger { background: #fef0f0; color: #f56c6c; border: 1px solid #fde2e2; }
.tag-warning { background: #fdf6ec; color: #e6a23c; border: 1px solid #faecd8; }
.tag-info { background: #ecf5ff; color: #409eff; border: 1px solid #d9ecff; }
.tag-success { background: #f0f9eb; color: #67c23a; border: 1px solid #e1f3d8; }
.tag-gray { background: #f4f4f5; color: #909399; border: 1px solid #e9e9eb; }

/* 级别指示条 */
.level-indicator {
  display: inline-block;
  width: 4px;
  height: 16px;
  border-radius: 2px;
  margin-right: 8px;
  vertical-align: middle;
}
.level-danger { background: #f56c6c; }
.level-warning { background: #e6a23c; }
.level-info { background: #409eff; }

/* 告警内容 */
.alert-message {
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: inline-block;
  vertical-align: middle;
}
.alert-message.critical { color: #f56c6c; font-weight: 500; }

/* 文本操作按钮 */
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
.btn-text.warning { color: #e6a23c; }
.btn-text.warning:hover { background: #fdf6ec; }
.btn-text.success { color: #67c23a; }
.btn-text.success:hover { background: #f0f9eb; }
.btn-text:disabled { opacity: 0.5; cursor: not-allowed; }
.healed-text { color: var(--text-secondary); font-size: 12px; padding: 4px 8px; }
/* 已忽略文本：灰色弱化，与「已恢复」区分 */
.ignored-text { color: #909399; }
/* 已关闭行的复选框：降低透明度，明确不可勾选 */
input[type='checkbox']:disabled { opacity: 0.4; cursor: not-allowed; }

/* 其他 */
.dim { color: var(--text-regular); }
.time { color: var(--text-secondary); font-size: 12px; }
.mono { font-family: Consolas, Monaco, monospace; }

.empty-state { text-align: center; padding: 50px 20px; }
.empty-state svg { width: 64px; height: 64px; fill: var(--text-disabled); margin-bottom: 16px; }
.empty-state p { font-size: 14px; color: var(--text-secondary); }

/* 详情弹窗 */
.detail-box { width: 640px; max-width: calc(100vw - 40px); }
.detail-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 40px 0;
  color: #409eff;
  font-size: 13px;
}
.detail-section { margin-bottom: 24px; }
.detail-section:last-child { margin-bottom: 0; }
.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px 24px;
  margin-bottom: 16px;
}
.detail-item { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.detail-item .label {
  color: var(--text-secondary);
  font-size: 12px;
  flex-shrink: 0;
}
.detail-content .label,
.section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 8px;
  display: block;
}
.content-box {
  background: var(--row-hover-bg);
  border: 1px solid var(--card-border);
  border-radius: 6px;
  padding: 12px 14px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-regular);
  white-space: pre-wrap;
  word-break: break-all;
}
.section-title {
  padding-bottom: 10px;
  border-bottom: 1px solid var(--card-border);
}
.log-item {
  padding: 12px 0;
  border-bottom: 1px dashed var(--card-border);
}
.log-item:last-child { border-bottom: none; }
.log-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 6px;
}
.log-meta { font-size: 12px; color: var(--text-secondary); }
.log-content { font-size: 13px; color: var(--text-regular); line-height: 1.6; }

/* ===== 告警上报弹窗 ===== */
/* 比默认弹窗更宽，容纳服务器/配置/类型等多字段表单 */
.report-box { width: 640px; max-width: calc(100vw - 40px); }
/* 异常上下文多行输入：与单选 input 高度区分，允许自由输入 JSON */
.report-textarea {
  height: auto;
  padding: 10px 12px;
  resize: vertical;
  line-height: 1.6;
}
/* 表单错误提示（全局 base.css 未定义，本组件补充） */
.error-tip {
  font-size: 12px;
  color: #f56c6c;
  margin-top: 4px;
}
</style>
