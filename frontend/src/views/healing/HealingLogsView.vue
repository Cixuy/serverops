<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from '@/utils/message'
import AppPagination from '@/components/common/AppPagination.vue'
// 自愈日志接口 + 新增弹窗下拉所需接口（服务器/监控配置/自愈规则）
import { getHealingActionLogs, createHealingActionLog, getHealingRules, getServerList, getMonitorConfigList } from '@/api'

/* ============================================================
 * 自愈日志页面
 * 依据原型设计（告警与自愈 / 自愈日志）落地，对接后端自愈操作日志接口
 * （接口文档：13-自愈日志管理）。
 *
 * 数据字段对齐后端 HealingActionLogInfo：
 *  - id / alertId / serverCode / configId / serviceName / ruleId / ruleName
 *  - triggerDetail(object 快照) / executeCommand / commandOutput
 *  - executionStatus(SUCCESS|FAILED|TIMEOUT|RETRYING) / retryCount
 *  - startedAt / finishedAt / durationMs
 * 分页为服务端分页；删除/重试/导出等操作后端接口暂未开放，保留占位提示。
 * ============================================================ */

// ===== 结果枚举（对齐 executionStatus） =====
const RESULT_OPTIONS = [
  { value: '', label: '全部结果' },
  { value: 'SUCCESS', label: '成功' },
  { value: 'FAILED', label: '失败' },
  { value: 'TIMEOUT', label: '超时' },
  { value: 'RETRYING', label: '重试中' },
]

// 结果 -> 标签样式 / 文案
function resultMeta(s) {
  switch (s) {
    case 'SUCCESS':
      return { cls: 'tag-success', text: '✓ 成功' }
    case 'FAILED':
      return { cls: 'tag-danger', text: '✗ 失败' }
    case 'TIMEOUT':
      return { cls: 'tag-danger', text: '✗ 超时' }
    case 'RETRYING':
      return { cls: 'tag-warning', text: '↻ 重试中' }
    default:
      return { cls: 'tag-gray', text: s || '未知' }
  }
}

// ===== 表格数据（服务端分页） =====
const tableData = ref([])
const total = ref(0)

// 概览统计（基于后端返回的 total 与当前结果做基础统计；如后端提供统计接口可进一步对齐）
const stats = reactive({
  total: 0,
  successRate: '0%',
  partial: 0,
  failed: 0,
})

// ===== 筛选与分页 =====
const query = reactive({
  keyword: '',
  result: '',
  startDate: '',
  endDate: '',
  pageNum: 1,
  pageSize: 10,
})

// ===== 加载数据 =====
async function loadData() {
  const params = {
    // 关键词映射到 serverCode（后端无 keyword 参数，仅支持 serverCode）
    serverCode: query.keyword || undefined,
    executionStatus: query.result || undefined,
    // 时间区间：日期字符串补全为 ISO8601 起始/结束
    startTime: query.startDate ? `${query.startDate}T00:00:00` : undefined,
    endTime: query.endDate ? `${query.endDate}T23:59:59` : undefined,
    pageNum: query.pageNum,
    pageSize: query.pageSize,
  }
  const res = await getHealingActionLogs(params)
  tableData.value = res.list || []
  total.value = res.total || 0
  updateStats(res.list || [])
}

onMounted(loadData)

// 概览统计：基于当前查询结果实时估算
function updateStats(list) {
  const success = list.filter((l) => l.executionStatus === 'SUCCESS').length
  const failed = list.filter((l) => l.executionStatus === 'FAILED' || l.executionStatus === 'TIMEOUT').length
  const retrying = list.filter((l) => l.executionStatus === 'RETRYING').length
  stats.total = total.value
  stats.successRate = list.length ? `${Math.round((success / list.length) * 1000) / 10}%` : '0%'
  stats.partial = retrying
  stats.failed = failed
}

// ===== 筛选交互 =====
// 时间区间校验：开始时间不能晚于结束时间
function validateTimeRange() {
  if (query.startDate && query.endDate && query.startDate > query.endDate) {
    ElMessage.warning('开始时间不能晚于结束时间')
    return false
  }
  return true
}

function handleSearch() {
  // 校验时间区间合法性，不合法则中止查询
  if (!validateTimeRange()) return
  query.pageNum = 1
  loadData()
}

function handleReset() {
  query.keyword = ''
  query.result = ''
  query.startDate = ''
  query.endDate = ''
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

// ===== 行详情弹窗（触发原因 / 执行动作 / 命令输出） =====
const detailVisible = ref(false)
const detailRow = ref(null)

function openDetail(l) {
  detailRow.value = l
  detailVisible.value = true
}

// ===== 时间格式化 =====
// 后端 startedAt/finishedAt 为 ISO8601（如 2019-08-24T14:15:22Z），统一转为本地可读时间
function formatTime(iso) {
  if (!iso) return '-'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

// ===== triggerDetail 格式化 =====
// 后端 triggerDetail 为 object 快照，包含触发时的指标快照（metrics）与服务状态（service_status）。
// 完整多行文本用于详情弹窗展示。
function triggerDetailFullText(d) {
  if (d == null) return ''
  if (typeof d === 'string') return d
  const lines = []
  const m = d.metrics
  if (m != null) {
    lines.push('【指标快照】')
    if (m.cpu_usage != null) lines.push(`CPU 使用率：${fmtPercent(m.cpu_usage)}`)
    if (m.memory_usage != null) lines.push(`内存使用率：${fmtPercent(m.memory_usage)}`)
    if (m.disk_usage != null) lines.push(`磁盘使用率：${fmtPercent(m.disk_usage)}`)
    if (m.network_in_kbps != null) lines.push(`网络入速率：${fmtKbps(m.network_in_kbps)}`)
    if (m.network_out_kbps != null) lines.push(`网络出速率：${fmtKbps(m.network_out_kbps)}`)
  }
  const st = d.service_status
  if (st != null) {
    lines.push('【服务状态】')
    lines.push(`端口监听：${st.port === 0 ? '未监听' : st.port}`)
    lines.push(`进程运行：${st.process === 0 ? '未运行' : st.process}`)
  }
  if (d.checked_at) lines.push(`检查时间：${formatTime(d.checked_at)}`)
  if (d.sampled_at) lines.push(`采样时间：${formatTime(d.sampled_at)}`)
  return lines.length ? lines.join('\n') : JSON.stringify(d)
}

// 百分比格式化：保留一位小数并加 %
function fmtPercent(v) {
  return `${Math.round(Number(v) * 10) / 10}%`
}

// 网络速率格式化：保留一位小数并加单位
function fmtKbps(v) {
  return `${Math.round(Number(v) * 10) / 10}kb/s`
}

// ============================================================
// 新增自愈日志弹窗（对应接口文档 13-自愈日志管理「POST 新增自愈操作日志」）
// 管理端可手动新增/补录一条自愈操作日志，落库 healing_action_log 表。
// ============================================================
const createVisible = ref(false)
const creating = ref(false)
const createErrors = reactive({})

// 弹窗下拉选项：服务器列表 / 监控配置 / 自愈规则（全量拉取，供选择）
const serverOptions = ref([])
const configOptions = ref([])
const ruleOptions = ref([])

// 新增表单初始值（字段对齐后端 HealingActionLogCreateRequest）
// 必填：serverCode / configId / ruleId / serviceName / executeCommand / executionStatus / startedAt
// 可选：alertId / triggerDetail / commandOutput / retryCount / finishedAt / durationMs
function emptyCreateForm() {
  return {
    serverCode: '',
    configId: undefined,
    serviceName: '',
    ruleId: undefined,
    alertId: '',
    executeCommand: '',
    commandOutput: '',
    executionStatus: 'SUCCESS',
    retryCount: 0,
    startedAt: '',
    finishedAt: '',
    durationMs: '',
    triggerDetail: '',
  }
}
const createForm = reactive(emptyCreateForm())

// 新增弹窗执行状态下拉（过滤掉“全部结果”占位项）
const CREATE_RESULT_OPTIONS = RESULT_OPTIONS.filter((r) => r.value)

// 打开新增弹窗：先重置表单并展示，再并行拉取下拉数据（任一失败不影响弹窗，可手动输入）
async function openCreate() {
  Object.assign(createForm, emptyCreateForm())
  Object.keys(createErrors).forEach((k) => delete createErrors[k])
  createVisible.value = true
  const [serverRes, configRes, ruleRes] = await Promise.allSettled([
    getServerList({ pageNum: 1, pageSize: 100 }),
    getMonitorConfigList({ pageNum: 1, pageSize: 100 }),
    getHealingRules({ pageNum: 1, pageSize: 100 }),
  ])
  serverOptions.value = serverRes.status === 'fulfilled' ? (serverRes.value?.list || []) : []
  configOptions.value = configRes.status === 'fulfilled' ? (configRes.value?.list || []) : []
  ruleOptions.value = ruleRes.status === 'fulfilled' ? (ruleRes.value?.list || []) : []
}

// 选择监控配置后自动联动填充服务名称（参考告警上报弹窗；手动填过则不覆盖）
function onConfigChange() {
  const cfg = configOptions.value.find((c) => c.id === createForm.configId)
  if (cfg && !createForm.serviceName) createForm.serviceName = cfg.serviceName || ''
}

// 校验新增表单必填项，返回是否通过
function validateCreate() {
  Object.keys(createErrors).forEach((k) => delete createErrors[k])
  const f = createForm
  if (!f.serverCode) createErrors.serverCode = '请选择服务器'
  if (!f.configId) createErrors.configId = '请选择监控配置'
  if (!f.ruleId) createErrors.ruleId = '请选择自愈规则'
  if (!f.serviceName.trim()) createErrors.serviceName = '请输入服务名称'
  if (!f.executeCommand.trim()) createErrors.executeCommand = '请输入执行命令'
  if (!f.executionStatus) createErrors.executionStatus = '请选择执行状态'
  if (!f.startedAt) createErrors.startedAt = '请选择开始时间'
  else if (f.finishedAt && new Date(f.finishedAt) < new Date(f.startedAt)) {
    createErrors.finishedAt = '结束时间不能早于开始时间'
  }
  // triggerDetail 若填写必须是合法的 JSON 对象（非数组、非标量）
  if (f.triggerDetail.trim()) {
    try {
      const parsed = JSON.parse(f.triggerDetail)
      if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
        throw new Error('not-object')
      }
    } catch {
      createErrors.triggerDetail = '必须是合法的 JSON 对象'
    }
  }
  return Object.keys(createErrors).length === 0
}

// 提交新增：校验 -> 构造 payload -> 调用 POST /healing-action-logs -> 成功刷新列表
async function submitCreate() {
  if (!validateCreate()) return
  creating.value = true
  try {
    const f = createForm
    const payload = {
      serverCode: f.serverCode,
      configId: Number(f.configId),
      serviceName: f.serviceName.trim(),
      ruleId: Number(f.ruleId),
      executeCommand: f.executeCommand.trim(),
      executionStatus: f.executionStatus,
      startedAt: new Date(f.startedAt).toISOString(),
    }
    // 可选字段：有值才携带，保持 payload 干净
    if (f.alertId) payload.alertId = Number(f.alertId)
    if (f.triggerDetail.trim()) payload.triggerDetail = JSON.parse(f.triggerDetail)
    if (f.commandOutput.trim()) payload.commandOutput = f.commandOutput
    if (f.retryCount !== '' && f.retryCount != null) payload.retryCount = Number(f.retryCount)
    if (f.finishedAt) payload.finishedAt = new Date(f.finishedAt).toISOString()
    if (f.durationMs !== '' && f.durationMs != null) payload.durationMs = Number(f.durationMs)

    await createHealingActionLog(payload)
    ElMessage.success('自愈日志新增成功')
    createVisible.value = false
    // 新增后刷新列表，让新日志立即出现在表格中
    loadData()
  } catch (e) {
    // 后端校验失败（如 serverCode 不能为空）或网络异常，展示具体错误信息
    ElMessage.error(e?.message || '新增自愈日志失败')
  } finally {
    creating.value = false
  }
}
</script>

<template>
  <div class="healing-logs">
    <!-- 页头：右侧提供“新增自愈日志”入口（管理端手动补录一条日志） -->
    <div class="page-header">
      <div class="page-title">自愈日志</div>
      <div class="page-actions">
        <button class="btn btn-primary" @click="openCreate">
          <svg viewBox="0 0 24 24"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" /></svg>
          新增自愈日志
        </button>
      </div>
    </div>

    <!-- 概览统计卡片 -->
    <div class="stat-row">
      <div class="stat-card green">
        <div class="stat-label">本周自愈总数</div>
        <div class="stat-value">{{ stats.total }}</div>
      </div>
      <div class="stat-card green">
        <div class="stat-label">成功率</div>
        <div class="stat-value">{{ stats.successRate }}</div>
      </div>
      <div class="stat-card orange">
        <div class="stat-label">重试中</div>
        <div class="stat-value">{{ stats.partial }}</div>
      </div>
      <div class="stat-card red">
        <div class="stat-label">失败</div>
        <div class="stat-value">{{ stats.failed }}</div>
      </div>
    </div>

    <!-- 筛选栏 -->
    <div class="filter-bar">
      <div class="filter-item">
        <span class="filter-label">搜索</span>
        <input
          v-model="query.keyword"
          class="filter-input"
          placeholder="服务器/IP/服务/规则名"
          @keyup.enter="handleSearch"
        />
      </div>
      <div class="filter-item">
        <span class="filter-label">结果</span>
        <select v-model="query.result" class="filter-select" @change="handleSearch">
          <option v-for="r in RESULT_OPTIONS" :key="r.value" :value="r.value">{{ r.label }}</option>
        </select>
      </div>
      <div class="filter-item">
        <span class="filter-label">时间</span>
        <!-- max/min 由浏览器原生限制：开始时间不能晚于结束时间，结束时间不能早于开始时间 -->
        <input
          v-model="query.startDate"
          type="date"
          class="filter-select date"
          :max="query.endDate || undefined"
          @change="validateTimeRange"
        />
        <span class="date-sep">至</span>
        <input
          v-model="query.endDate"
          type="date"
          class="filter-select date"
          :min="query.startDate || undefined"
          @change="validateTimeRange"
        />
      </div>
      <button class="btn btn-primary btn-sm" @click="handleSearch">筛选</button>
      <button class="btn btn-default btn-sm" @click="handleReset">重置</button>
    </div>

    <!-- 日志表格 -->
    <div class="table-card">
      <div class="table-toolbar">
        <h3>自愈操作日志（{{ total }} 条）</h3>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>时间</th>
              <th>服务器</th>
              <th>服务</th>
              <th>触发规则</th>
              <th>结果</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="l in tableData" :key="l.id">
              <td class="time-cell">{{ formatTime(l.startedAt) }}</td>
              <td><strong>{{ l.serverCode }}</strong></td>
              <td>{{ l.serviceName }}</td>
              <td>{{ l.ruleName || '-' }}</td>
              <td>
                <span class="tag" :class="resultMeta(l.executionStatus).cls">{{ resultMeta(l.executionStatus).text }}</span>
              </td>
              <td>
                <button class="btn-text primary" @click="openDetail(l)">详情</button>
              </td>
            </tr>
            <tr v-if="!tableData.length">
              <td colspan="6">
                <div class="empty-state">
                  <svg viewBox="0 0 24 24"><path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8l-6-6zM6 20V4h7v5h5v11H6z" /></svg>
                  <p>暂无符合条件的自愈日志</p>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
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

    <!-- 行详情弹窗：展示触发原因 / 执行动作 / 命令输出 完整内容 -->
    <div v-if="detailVisible && detailRow" class="modal-mask">
      <div class="modal-box detail-modal">
        <div class="modal-header">
          <h3>自愈日志详情</h3>
          <button class="modal-close" @click="detailVisible = false">
            <svg viewBox="0 0 24 24"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z" /></svg>
          </button>
        </div>
        <div class="modal-body">
          <div class="detail-item">
            <div class="detail-label">触发原因</div>
            <div class="detail-content">{{ triggerDetailFullText(detailRow.triggerDetail) || '-' }}</div>
          </div>
          <div class="detail-item">
            <div class="detail-label">执行动作</div>
            <div class="detail-content mono">{{ detailRow.executeCommand || '-' }}</div>
          </div>
          <div class="detail-item">
            <div class="detail-label">命令输出</div>
            <div class="detail-content mono">{{ detailRow.commandOutput || '-' }}</div>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-default" @click="detailVisible = false">关闭</button>
        </div>
      </div>
    </div>

    <!-- 新增自愈日志弹窗（复用全局 modal/form 样式，弹窗宽度本地加宽） -->
    <!-- 取消点击遮罩关闭：避免填写内容时误点外部导致输入丢失，仅通过 × 或取消按钮关闭 -->
    <div v-if="createVisible" class="modal-mask">
      <div class="modal-box create-modal">
        <div class="modal-header">
          <h3>新增自愈日志</h3>
          <button class="modal-close" @click="createVisible = false">
            <svg viewBox="0 0 24 24"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z" /></svg>
          </button>
        </div>
        <div class="modal-body">
          <div class="form-row">
            <div class="form-item">
              <label>服务器 <span class="required">*</span></label>
              <select v-model="createForm.serverCode" class="form-input">
                <option value="">请选择服务器</option>
                <option v-for="s in serverOptions" :key="s.id" :value="s.serverCode">
                  {{ s.hostname || s.serverCode }}
                </option>
              </select>
              <div v-if="createErrors.serverCode" class="error-tip">{{ createErrors.serverCode }}</div>
            </div>
            <div class="form-item">
              <label>监控配置 <span class="required">*</span></label>
              <!-- 选择配置后自动填充服务名称 -->
              <select v-model="createForm.configId" class="form-input" @change="onConfigChange">
                <option :value="undefined">请选择监控配置</option>
                <option v-for="c in configOptions" :key="c.id" :value="c.id">
                  {{ c.serviceName }}（{{ c.serverCode }}）
                </option>
              </select>
              <div v-if="createErrors.configId" class="error-tip">{{ createErrors.configId }}</div>
            </div>
          </div>

          <div class="form-row">
            <div class="form-item">
              <label>服务名称 <span class="required">*</span></label>
              <input v-model="createForm.serviceName" class="form-input" placeholder="如：Nginx（选择配置后自动填充）" />
              <div v-if="createErrors.serviceName" class="error-tip">{{ createErrors.serviceName }}</div>
            </div>
            <div class="form-item">
              <label>自愈规则 <span class="required">*</span></label>
              <select v-model="createForm.ruleId" class="form-input">
                <option :value="undefined">请选择自愈规则</option>
                <option v-for="r in ruleOptions" :key="r.id" :value="r.id">{{ r.ruleName }}</option>
              </select>
              <div v-if="createErrors.ruleId" class="error-tip">{{ createErrors.ruleId }}</div>
            </div>
          </div>

          <div class="form-row">
            <div class="form-item">
              <label>执行状态 <span class="required">*</span></label>
              <select v-model="createForm.executionStatus" class="form-input">
                <option v-for="r in CREATE_RESULT_OPTIONS" :key="r.value" :value="r.value">{{ r.label }}</option>
              </select>
              <div v-if="createErrors.executionStatus" class="error-tip">{{ createErrors.executionStatus }}</div>
            </div>
            <div class="form-item">
              <label>重试次数</label>
              <input v-model.number="createForm.retryCount" type="number" min="0" class="form-input" placeholder="从 0 开始" />
            </div>
          </div>

          <div class="form-row">
            <div class="form-item">
              <label>开始时间 <span class="required">*</span></label>
              <input v-model="createForm.startedAt" type="datetime-local" class="form-input" />
              <div v-if="createErrors.startedAt" class="error-tip">{{ createErrors.startedAt }}</div>
            </div>
            <div class="form-item">
              <label>结束时间</label>
              <input v-model="createForm.finishedAt" type="datetime-local" class="form-input" />
              <div v-if="createErrors.finishedAt" class="error-tip">{{ createErrors.finishedAt }}</div>
            </div>
          </div>

          <div class="form-row">
            <div class="form-item">
              <label>关联告警 ID</label>
              <input v-model="createForm.alertId" type="number" min="1" class="form-input" placeholder="可选" />
            </div>
            <div class="form-item">
              <label>执行耗时（毫秒）</label>
              <input v-model="createForm.durationMs" type="number" min="0" class="form-input" placeholder="可选" />
            </div>
          </div>

          <div class="form-item">
            <label>执行命令 <span class="required">*</span></label>
            <input v-model="createForm.executeCommand" class="form-input mono" placeholder="如：systemctl restart nginx" />
            <div v-if="createErrors.executeCommand" class="error-tip">{{ createErrors.executeCommand }}</div>
          </div>

          <div class="form-item">
            <label>命令输出</label>
            <textarea v-model="createForm.commandOutput" class="form-textarea mono" rows="2" placeholder="可选，命令标准输出/错误输出"></textarea>
          </div>

          <div class="form-item">
            <label>触发详情（JSON）</label>
            <textarea
              v-model="createForm.triggerDetail"
              class="form-textarea mono"
              rows="3"
              placeholder='可选，如：{"error_msg":"Connection refused","pid":1234}'
            ></textarea>
            <div v-if="createErrors.triggerDetail" class="error-tip">{{ createErrors.triggerDetail }}</div>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-default" @click="createVisible = false">取消</button>
          <button class="btn btn-primary" :disabled="creating" @click="submitCreate">
            {{ creating ? '提交中...' : '保存日志' }}
          </button>
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
  line-height: 1.2;
  color: var(--text-primary);
}
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
.filter-select { width: 120px; }
.filter-select.date { width: 140px; }
.date-sep { color: #909399; }

/* ===== 表格 ===== */
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
.table-toolbar h3 {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.table-wrap { overflow-x: auto; }

table { width: 100%; border-collapse: collapse; }
thead th {
  text-align: left;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  padding: 10px 16px;
  background: var(--table-head-bg);
  border-bottom: 1px solid var(--card-border);
  white-space: nowrap;
}
tbody td {
  padding: 12px 16px;
  font-size: 13px;
  color: var(--text-primary);
  border-bottom: 1px solid var(--card-border);
  vertical-align: middle;
  white-space: nowrap;
}
tbody tr:hover { background: var(--row-hover-bg); }
tbody tr:last-child td { border-bottom: none; }

/* 单元格细分样式 */
.time-cell { color: #a3a6b0; }

/* ===== 标签 ===== */
.tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 10px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
}
.tag-success { background: #f0f9eb; color: #67c23a; border: 1px solid #e1f3d8; }
.tag-danger { background: #fef0f0; color: #f56c6c; border: 1px solid #fde2e2; }
.tag-warning { background: #fdf6ec; color: #e6a23c; border: 1px solid #faecd8; }
.tag-info { background: #ecf5ff; color: #409eff; border: 1px solid #d9ecff; }
.tag-gray { background: #f4f4f5; color: #909399; border: 1px solid #e9e9eb; }

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
  white-space: nowrap;
}
.btn-text.primary { color: #409eff; }
.btn-text.primary:hover { background: #ecf5ff; }
.btn-text.danger { color: #f56c6c; }
.btn-text.danger:hover { background: #fef0f0; }

/* ===== 操作栏单行 ===== */
.table-actions {
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
  gap: 2px;
  white-space: nowrap;
  min-width: max-content;
}
.table-actions .btn-text {
  flex-shrink: 0;
}

/* ===== 空状态 ===== */
.empty-state { text-align: center; padding: 50px 20px; }
.empty-state svg { width: 64px; height: 64px; fill: var(--text-disabled); margin-bottom: 16px; }
.empty-state p { font-size: 14px; color: var(--text-secondary); }

/* ===== 行详情弹窗 ===== */
.detail-modal { width: 680px; }
.detail-item { margin-bottom: 16px; }
.detail-item:last-child { margin-bottom: 0; }
.detail-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-secondary);
  margin-bottom: 6px;
}
.detail-content {
  padding: 10px 12px;
  background: var(--input-bg);
  border: 1px solid var(--input-border);
  border-radius: 6px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-primary);
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 220px;
  overflow-y: auto;
}
.detail-content.mono {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
}

/* ===== 新增自愈日志弹窗（复用全局 modal/form 样式，此处仅补充） ===== */
.create-modal { width: 640px; }
.form-textarea {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--input-border);
  border-radius: 6px;
  font-size: 13px;
  color: var(--text-primary);
  outline: none;
  background: var(--input-bg);
  font-family: inherit;
  line-height: 1.6;
  resize: vertical;
  transition: border-color 0.2s;
}
.form-textarea:focus { border-color: #409eff; }
/* 命令输入框使用等宽字体，方便阅读命令与输出 */
.mono { font-family: Consolas, Monaco, monospace; font-size: 12px; }
/* 表单错误提示（全局 base.css 未定义，本组件补充） */
.error-tip {
  font-size: 12px;
  color: #f56c6c;
  margin-top: 4px;
}

/* ===== 响应式 ===== */
@media (max-width: 900px) {
  .stat-row { grid-template-columns: repeat(2, 1fr); }
}
</style>
