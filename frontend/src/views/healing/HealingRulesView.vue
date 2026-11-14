<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from '@/utils/message'
import AppPagination from '@/components/common/AppPagination.vue'
import {
  getHealingRules,
  createHealingRule,
  updateHealingRule,
  deleteHealingRule,
  toggleHealingRuleStatus,
} from '@/api'

/* ============================================================
 * 自愈规则页面
 * 依据原型设计（告警与自愈 / 自愈规则）落地，对接后端自愈规则接口
 * （接口文档：11-自愈规则管理）。
 *
 * 数据字段对齐后端 HealingRuleInfo：
 *  - ruleName / description / triggerConfig(对象) / executeCommand /
 *    timeoutSeconds / maxRetries / retryInterval / status
 * triggerConfig 为组合条件表达式树：{ logic, conditions[] }
 * conditions 支持三类：METRIC(指标) / SERVICE_STATUS(服务状态) /
 * LOG_MATCH(日志匹配，含 log_file_path 与 regex_pattern)
 * ============================================================ */

// ===== 条件来源 / 运算符选项 =====
// 条件来源映射到后端 condition.type：METRIC / SERVICE_STATUS / LOG_MATCH。
// METRIC 类额外绑定后端指标字段名 metric（与数据库 server_metrics / server_disk_metrics 对齐）：
//   cpu_usage / memory_usage / network_in_kbps / network_out_kbps / disk_usage
// unit 为指标单位（来自数据库表设计），仅用于编辑器输入提示展示，保存时不提交该字段（后端存数值）。
const COND_TYPES = [
  // 指标类：metric 为后端字段名，unit 为展示单位
  { label: 'CPU使用率', type: 'METRIC', metric: 'cpu_usage', unit: '%' },
  { label: '内存使用率', type: 'METRIC', metric: 'memory_usage', unit: '%' },
  { label: '磁盘使用率', type: 'METRIC', metric: 'disk_usage', unit: '%' },
  { label: '网络上行', type: 'METRIC', metric: 'network_out_kbps', unit: 'KB/s' },
  { label: '网络下行', type: 'METRIC', metric: 'network_in_kbps', unit: 'KB/s' },
  // 服务状态类：metric 标识具体检测维度（进程/端口），供后端区分
  { label: '进程状态', type: 'SERVICE_STATUS', metric: 'process' },
  { label: '端口状态', type: 'SERVICE_STATUS', metric: 'port' },
  // 日志匹配类
  { label: '日志内容', type: 'LOG_MATCH' },
]

// 日志匹配条件需额外提供日志文件路径
function condTypeOf(label) {
  return COND_TYPES.find((c) => c.label === label)?.type || 'METRIC'
}
function condLabelOf(type) {
  return COND_TYPES.find((c) => c.type === type)?.label || type
}
// 根据后端指标字段名 metric 查找其展示单位（来自数据库 server_metrics / server_disk_metrics）
function condUnitOf(metric) {
  return COND_TYPES.find((c) => c.metric === metric)?.unit || ''
}

// 运算符选项（保存到 condition.operator）
const OPERATOR_OPTIONS = ['=', '>', '<', '>=', '<=', '包含', '不包含', '匹配']

// ===== 表格数据 =====
const tableData = ref([])
const total = ref(0)

// 启用/禁用统计
const enabledCount = computed(() => tableData.value.filter((r) => r.status === 1).length)
const disabledCount = computed(() => tableData.value.length - enabledCount.value)

// ===== 筛选与分页 =====
const query = reactive({
  keyword: '',
  status: '',
  pageNum: 1,
  // 默认每页条数对齐 AppPagination 的下拉选项（10/20/50/100），避免显示异常
  pageSize: 10,
})

// ===== 加载数据 =====
async function loadData() {
  const params = {
    // 后端 ruleName 为模糊搜索规则名称；此处把关键词传给 ruleName（后端无跨条件搜索，保留名称搜索即可）
    ruleName: query.keyword || undefined,
    status: query.status === '' ? undefined : Number(query.status),
    pageNum: query.pageNum,
    pageSize: query.pageSize,
  }
  const res = await getHealingRules(params)
  tableData.value = res.list || []
  total.value = res.total || 0
}

onMounted(loadData)

// ===== 筛选交互 =====
function handleSearch() {
  query.pageNum = 1
  loadData()
}

function handleReset() {
  query.keyword = ''
  query.status = ''
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

// ===== triggerConfig 解析 =====
// 后端 triggerConfig 可能是「嵌套表达式树」或「扁平单条件」两种形态（见真实接口返回样例）：
//   形态A（表达式树）：{ logic:'AND', conditions:[ {type,value,operator,metric}, {logic:'OR',conditions:[...]} ] }
//   形态B（扁平单条件）：{ metric:'cpu_usage', threshold:90, operator:'>', duration:60 }（无 conditions/type）
// 这里做递归展平，输出带括号与逻辑符的展示条目，供表格「触发条件」列渲染。

// 根据后端指标字段名 metric 匹配其展示名称（如 memory_usage → 内存使用率），未命中返回空
function condLabelByMetric(metric) {
  if (!metric) return ''
  return COND_TYPES.find((x) => x.metric === metric)?.label || ''
}

// 单个条件归一化为展示对象
function parseSingleCondition(c) {
  const type = c.type || 'METRIC'
  const metric = c.metric || c.metric_name || c.metricName || ''
  // 优先按 metric 匹配具体指标名，避免同 type=METRIC 的条件全部被映射成第一个指标（CPU使用率）
  const label = condLabelByMetric(metric) || condLabelOf(type)
  // 取值优先级：value > threshold；LOG_MATCH 无顶层 value，用 regex_pattern 作为比较值
  let value = c.value
  if (value == null && type === 'LOG_MATCH') value = c.regex_pattern || ''
  if (value == null) value = c.threshold ?? c.duration ?? ''
  // 指标名（METRIC 类型）与其单位
  const unit = condUnitOf(metric)
  // 运算符归一化：后端常用 ==/!=，前端统一展示 = / ≠
  const op = (c.operator || '=').replace('==', '=').replace('!=', '≠')
  return { label, operator: op, value: value != null ? String(value) : '', metric, unit }
}

// 递归展平表达式树
function flattenTrigger(node, out, logic) {
  const list = node.conditions || [node] // 兼容扁平单条件形态
  list.forEach((c, i) => {
    // 逻辑连接符：取子项自带 logic，否则沿用父组 logic，首项默认 AND
    const conn = i === 0 ? (logic || node.logic || 'AND') : (c.logic || node.logic || 'AND')
    if (c.conditions && c.conditions.length) {
      // 嵌套条件组：输出左括号 → 递归 → 右括号
      out.push({ isParen: true, side: 'open', logic: conn })
      flattenTrigger(c, out, c.logic || node.logic)
      out.push({ isParen: true, side: 'close', logic: '' })
    } else {
      out.push({ ...parseSingleCondition(c), logic: conn })
    }
  })
}

function parseTriggerConfig(triggerConfig) {
  const out = []
  flattenTrigger(triggerConfig || {}, out)
  return out
}

// ===== 触发条件自适应展示 =====
// 将解析结果转成结构化片段数组，供模板用 flex 布局渲染。
// 每个逻辑符（AND/OR）与每个条件块均为独立片段并 nowrap，从而保证
// 「OR」不会被拆行成 O/R 两行，整体随容器宽度自适应换行。
// 返回片段类型：{ type: 'logic' | 'cond' | 'paren', text }
function triggerConfigParts(triggerConfig) {
  const items = parseTriggerConfig(triggerConfig)
  const parts = []
  let hasContent = false // 是否已输出过条件/括号，用于跳过首条多余逻辑符
  let afterParen = false // 是否紧跟左括号，括号内的首条条件不再重复输出逻辑符
  items.forEach((it) => {
    if (it.isParen) {
      if (it.side === 'open') {
        // 左括号前若带逻辑符（如 OR），先输出逻辑符片段，再输出括号
        if (hasContent && it.logic) parts.push({ type: 'logic', text: it.logic })
        parts.push({ type: 'paren', text: '(' })
        hasContent = true
        afterParen = true
      } else {
        parts.push({ type: 'paren', text: ')' })
        afterParen = false
      }
      return
    }
    // 逻辑连接符片段：紧随左括号之后的首条条件，或首个条件前不重复输出逻辑符
    if (hasContent && !afterParen && it.logic) parts.push({ type: 'logic', text: it.logic })
    // 条件片段：名称(指标) 运算符 值(单位)
    const label = it.metric ? `${it.label}(${it.metric})` : it.label
    const unit = it.unit ? it.unit : ''
    parts.push({ type: 'cond', text: `${label} ${it.operator} ${it.value}${unit}` })
    hasContent = true
    afterParen = false
  })
  return parts
}

// ===== 启用 / 禁用 =====
async function toggleStatus(r) {
  const newStatus = r.status === 1 ? 0 : 1
  try {
    await toggleHealingRuleStatus(r.id, newStatus)
    r.status = newStatus
    ElMessage.success(`「${r.ruleName}」已${newStatus === 1 ? '启用' : '禁用'}`)
    loadData()
  } catch {
    ElMessage.error('操作失败')
  }
}

// ===== 新建 / 编辑 =====
// 可视化规则编辑器常驻于页面下方，默认显示。点击「新建规则」或列表中的「编辑」时，
// 填充对应表单内容，并自动滚动定位到编辑器区域。
const editorTitle = ref('新建自愈规则')
const editingId = ref(null)
const editorRef = ref(null)

// 编辑器表单（对应 HealingRuleCreateRequest / UpdateRequest 字段）
const form = reactive({
  ruleName: '',
  description: '',
  conditions: [], // 前端可视化条件 [{ label, operator, value, logPath }]
  executeCommand: '',
  timeoutSeconds: 60,
  maxRetries: 3,
  retryInterval: 30,
})

// 滚动到页面下方的编辑器区域（点击新建/编辑时定位）
function scrollToEditor() {
  requestAnimationFrame(() => {
    editorRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  })
}

// 重置表单为「新建」状态（供「清空」按钮使用）
function resetForm() {
  editorTitle.value = '新建自愈规则'
  editingId.value = null
  Object.assign(form, {
    ruleName: '',
    description: '',
    conditions: [],
    executeCommand: '',
    timeoutSeconds: 60,
    maxRetries: 3,
    retryInterval: 30,
  })
}

// 清空表单并打开新建
function openAdd() {
  resetForm()
  scrollToEditor()
}

// 编辑：回填表单
function openEdit(r) {
  editorTitle.value = '编辑自愈规则'
  editingId.value = r.id
  Object.assign(form, {
    ruleName: r.ruleName,
    description: r.description || '',
    // 编辑回填仅保留普通条件块（过滤括号/逻辑符项），并保留指标名便于保存时还原
    conditions: parseTriggerConfig(r.triggerConfig)
      .filter((c) => !c.isParen)
      .map((c) => ({ label: c.label, operator: c.operator, value: c.value, metric: c.metric || '' })),
    executeCommand: r.executeCommand || '',
    timeoutSeconds: r.timeoutSeconds || 60,
    maxRetries: r.maxRetries || 3,
    retryInterval: r.retryInterval || 30,
  })
  scrollToEditor()
}

// ===== 可视化编辑器：条件块操作 =====
const availableSources = COND_TYPES.map((c) => c.label)

// 从可用条件库中添加条件块到编辑器
function addConditionBlock(label) {
  // 同源条件已存在则不再重复添加（避免列表冗余）
  if (form.conditions.some((c) => c.label === label)) return
  // 从 COND_TYPES 读取该来源绑定的后端 metric 字段名（METRIC 类），保证保存时指标名不丢失
  const type = COND_TYPES.find((c) => c.label === label)
  form.conditions.push({ label, operator: '=', value: '', metric: type?.metric || '' })
}

// 编辑器内切换条件来源时，同步更新该条件绑定的后端 metric（保证保存的指标名正确）
function onConditionLabelChange(c) {
  const type = COND_TYPES.find((x) => x.label === c.label)
  c.metric = type?.metric || ''
  // 切换来源后重置比较值，避免跨类型残留不合理的阈值
  c.value = ''
}

// 移除编辑器中的条件块
function removeCondition(index) {
  form.conditions.splice(index, 1)
}

// 将前端条件数组转成后端 triggerConfig 表达式树
function buildTriggerConfig() {
  return {
    logic: 'AND',
    conditions: form.conditions.map((c) => {
      const type = condTypeOf(c.label)
      // LOG_MATCH 类型需携带日志文件路径与正则模式
      if (type === 'LOG_MATCH') {
        return {
          type,
          operator: c.operator,
          value: c.value,
          log_file_path: c.logPath || '',
          regex_pattern: c.value,
        }
      }
      // METRIC 类型携带指标名（如 cpu_usage/memory_usage），value 作为阈值
      return {
        type,
        operator: c.operator,
        value: c.value,
        metric: c.metric || '',
      }
    }),
  }
}

// ===== 保存规则 =====
async function submitForm() {
  if (!form.ruleName.trim()) {
    ElMessage.warning('请输入规则名称')
    return
  }
  if (!form.conditions.length) {
    ElMessage.warning('请至少添加一个触发条件')
    return
  }
  if (form.conditions.some((c) => !c.value)) {
    ElMessage.warning('请完善所有触发条件的比较值')
    return
  }
  if (!form.executeCommand.trim()) {
    ElMessage.warning('请输入自愈执行命令')
    return
  }

  // 组装提交数据（对齐后端 HealingRuleCreateRequest / UpdateRequest）
  const payload = {
    ruleName: form.ruleName.trim(),
    description: form.description.trim(),
    triggerConfig: buildTriggerConfig(),
    executeCommand: form.executeCommand.trim(),
    timeoutSeconds: Number(form.timeoutSeconds) || 60,
    maxRetries: Number(form.maxRetries) || 3,
    retryInterval: Number(form.retryInterval) || 30,
  }

  try {
    if (editingId.value) {
      await updateHealingRule(editingId.value, payload)
      ElMessage.success(`规则「${payload.ruleName}」已更新`)
    } else {
      await createHealingRule(payload)
      ElMessage.success(`规则「${payload.ruleName}」已创建`)
    }
    // 保存成功后回到「新建」状态（编辑器常驻，不清除页面区域）
    resetForm()
    loadData()
  } catch {
    ElMessage.error('保存失败')
  }
}

// ===== 删除 =====
const deleteTarget = ref(null)

function askDelete(r) {
  deleteTarget.value = r
}

async function confirmDelete() {
  try {
    await deleteHealingRule(deleteTarget.value.id)
    ElMessage.success(`规则「${deleteTarget.value.ruleName}」已删除`)
    deleteTarget.value = null
    loadData()
  } catch {
    ElMessage.error('删除失败')
  }
}
</script>

<template>
  <div class="healing-rules">
    <!-- 页头：标题 + 操作按钮 -->
    <div class="page-header">
      <div class="page-title">自愈规则管理</div>
      <div class="page-actions">
        <button class="btn btn-default" @click="handleReset">
          <svg viewBox="0 0 24 24"><path d="M17.65 6.35A7.958 7.958 0 0012 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0112 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z" /></svg>
          重置筛选
        </button>
        <button class="btn btn-primary" @click="openAdd">
          <svg viewBox="0 0 24 24"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" /></svg>
          新建规则
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
          placeholder="规则名称/条件"
          style="width: 200px;"
          @keyup.enter="handleSearch"
        />
      </div>
      <div class="filter-item">
        <span class="filter-label">状态</span>
        <select v-model="query.status" class="filter-select" @change="handleSearch">
          <option value="">全部</option>
          <option value="1">启用</option>
          <option value="0">禁用</option>
        </select>
      </div>
      <button class="btn btn-primary btn-sm" @click="handleSearch">搜索</button>
    </div>

    <!-- 规则表格 -->
    <div class="table-card">
      <div class="table-toolbar">
        <h3>自愈规则列表 ({{ total }}条)</h3>
        <span class="table-tip">启用 {{ enabledCount }} / 禁用 {{ disabledCount }}</span>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>规则名称</th>
              <th>触发条件</th>
              <th>执行命令</th>
              <th>超时/重试</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in tableData" :key="r.id">
              <td>
                <strong>{{ r.ruleName }}</strong>
                <div v-if="r.description" class="rule-desc">{{ r.description }}</div>
              </td>
              <td>
                <!-- 触发条件：按片段自适应换行渲染，逻辑符/条件块各自 nowrap 防止拆行（如 OR 不会被拆成两行） -->
                <div class="cond-flex">
                  <template v-for="(p, i) in triggerConfigParts(r.triggerConfig)" :key="i">
                    <span v-if="p.type === 'logic'" class="cond-logic">{{ p.text }}</span>
                    <span v-else-if="p.type === 'paren'" class="cond-paren">{{ p.text }}</span>
                    <span v-else class="cond-cond">{{ p.text }}</span>
                  </template>
                </div>
              </td>
              <td class="mono cmd">{{ r.executeCommand }}</td>
              <td class="time-cell">
                <span>超时 {{ r.timeoutSeconds }}s</span>
                <span>重试 {{ r.maxRetries }}次</span>
              </td>
              <td>
                <span class="tag" :class="r.status === 1 ? 'tag-success' : 'tag-gray'">
                  {{ r.status === 1 ? '启用' : '禁用' }}
                </span>
              </td>
              <td>
                <!-- 操作栏：三个按钮用 flex + nowrap 强制保持一行，避免被挤成多行 -->
                <div class="table-actions">
                  <button class="btn-text" :class="r.status === 1 ? 'warning' : 'primary'" @click="toggleStatus(r)">
                    {{ r.status === 1 ? '停用' : '启用' }}
                  </button>
                  <button class="btn-text primary" @click="openEdit(r)">编辑</button>
                  <button class="btn-text danger" @click="askDelete(r)">删除</button>
                </div>
              </td>
            </tr>
            <tr v-if="!tableData.length">
              <td colspan="6">
                <div class="empty-state">
                  <svg viewBox="0 0 24 24"><path d="M4 6h16v2H4zm0 5h16v2H4zm0 5h16v2H4z" /></svg>
                  <p>暂无符合条件的自愈规则</p>
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

    <!-- 可视化规则编辑器（常驻页面下方，点击「新建/编辑」填充内容） -->
    <div ref="editorRef" class="editor-section">
      <div class="editor-header">
        <div class="editor-title">{{ editorTitle }}</div>
      </div>
      <div class="editor-body">
        <!-- 基础信息 -->
        <div class="form-row">
          <div class="form-item">
            <label>规则名称 <span class="required">*</span></label>
            <input v-model="form.ruleName" class="form-input" placeholder="如：MySQL 锁文件恢复" />
          </div>
          <div class="form-item">
            <label>规则描述</label>
            <input v-model="form.description" class="form-input" placeholder="可选，说明规则用途" />
          </div>
        </div>

        <div class="editor-grid">
            <!-- 左侧：条件构建 -->
            <div class="editor-panel">
              <div class="panel-title">触发条件</div>
              <div class="panel-subtitle">组合 IF-THEN 逻辑，满足全部条件时触发自愈</div>

              <!-- 已选条件块 -->
              <div class="drop-zone" :class="{ 'has-items': form.conditions.length }">
                <template v-if="form.conditions.length">
                  <template v-for="(c, i) in form.conditions" :key="i">
                    <span v-if="i > 0" class="cond-logic">AND</span>
                    <div class="drag-block source">
                      <span class="drag-handle">⋮⋮</span>
                      <select v-model="c.label" class="cond-select" @change="onConditionLabelChange(c)">
                        <option v-for="s in availableSources" :key="s" :value="s">{{ s }}</option>
                      </select>
                      <span class="cond-block cond-operator">
                        <select v-model="c.operator" class="cond-select">
                          <option v-for="op in OPERATOR_OPTIONS" :key="op" :value="op">{{ op }}</option>
                        </select>
                      </span>
                      <span class="cond-block cond-value">
                        <input v-model="c.value" class="cond-input" placeholder="比较值" />
                        <!-- 单位后缀：指标类条件显示对应单位（如 % / KB/s），服务状态/日志类无单位 -->
                        <span v-if="condUnitOf(c.metric)" class="cond-unit">{{ condUnitOf(c.metric) }}</span>
                      </span>
                      <button class="cond-remove" title="移除条件" @click="removeCondition(i)">×</button>
                    </div>
                  </template>
                </template>
                <span v-else class="drop-placeholder">点击下方条件块添加触发条件</span>
              </div>

              <div class="cond-pool-title">可用条件块：</div>
              <div class="cond-pool">
                <div
                  v-for="s in availableSources"
                  :key="s"
                  class="drag-block source pool-item"
                  :class="{ disabled: form.conditions.some((c) => c.label === s) }"
                  @click="addConditionBlock(s)"
                >
                  <span class="drag-handle">＋</span>
                  <span>{{ s }}</span>
                </div>
              </div>
            </div>

            <!-- 右侧：动作配置 -->
            <div class="editor-panel">
              <div class="panel-title">执行动作</div>
              <div class="panel-subtitle">配置匹配条件后执行的自愈操作</div>

              <div class="form-row">
                <label>自愈执行命令 <span class="required">*</span></label>
                <input
                  v-model="form.executeCommand"
                  class="form-input mono"
                  placeholder="如：net stop MySQL80 && net start MySQL80"
                />
              </div>

              <div class="form-row">
                <label>执行说明</label>
                <div class="form-tip">
                  命令在匹配触发条件后自动执行；超时、重试与重试间隔参数用于控制自愈任务的执行策略。
                </div>
              </div>

              <div class="form-row form-inline">
                <div class="form-item">
                  <label>执行超时（秒）</label>
                  <input v-model.number="form.timeoutSeconds" class="form-input" type="number" style="width: 100px;" />
                </div>
                <div class="form-item">
                  <label>最大重试次数</label>
                  <input v-model.number="form.maxRetries" class="form-input" type="number" style="width: 100px;" />
                </div>
                <div class="form-item">
                  <label>重试间隔（秒）</label>
                  <input v-model.number="form.retryInterval" class="form-input" type="number" style="width: 100px;" />
                </div>
              </div>
            </div>
          </div>
      </div>
      <div class="editor-footer">
        <button class="btn btn-default" @click="resetForm">清空</button>
        <button class="btn btn-primary" @click="submitForm">保存规则</button>
      </div>
    </div>

    <!-- 删除确认弹窗 -->
    <div v-if="deleteTarget" class="modal-mask" @click.self="deleteTarget = null">
      <div class="modal-box confirm-box">
        <div class="modal-header">
          <h3>删除确认</h3>
          <button class="modal-close" @click="deleteTarget = null">
            <svg viewBox="0 0 24 24"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z" /></svg>
          </button>
        </div>
        <div class="modal-body">
          确定删除自愈规则
          <strong>{{ deleteTarget.ruleName }}</strong>
          吗？删除后该规则将不再生效。
        </div>
        <div class="modal-footer">
          <button class="btn btn-default" @click="deleteTarget = null">取消</button>
          <button class="btn btn-danger" @click="confirmDelete">确定删除</button>
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
.btn svg { width: 14px; height: 14px; fill: currentColor; }

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
.filter-select { width: 170px; }

/* ===== 表格 ===== */
.table-card {
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: 10px;
  overflow: hidden;
  margin-bottom: 20px;
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
.table-tip {
  font-size: 12px;
  color: var(--text-secondary);
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
  padding: 14px 16px;
  font-size: 13px;
  color: var(--text-primary);
  border-bottom: 1px solid var(--card-border);
  vertical-align: middle;
}
tbody tr:hover { background: var(--row-hover-bg); }
tbody tr:last-child td { border-bottom: none; }

/* ===== 触发条件（表格 flex 自适应展示） ===== */
.cond-flex {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px 10px; /* 行间距 6px，逻辑符与条件块之间横向 10px */
  font-size: 12px;
  color: var(--text-regular);
  line-height: 1.5;
}
/* 逻辑符（AND/OR）：独立 nowrap，防止被拆行成 O/R 两行 */
.cond-logic {
  display: inline-flex;
  align-items: center;
  white-space: nowrap;
  font-size: 11px;
  color: #8e5cf7;
  font-weight: 700;
  letter-spacing: 0.5px;
  padding: 1px 4px;
}
/* 单个条件：整体 nowrap，名称(指标) 运算符 值 不会被拆开 */
.cond-cond {
  display: inline-flex;
  align-items: center;
  white-space: nowrap;
  background: #f0f4ff;
  border: 1px solid #d9ecff;
  color: #303133;
  border-radius: 4px;
  padding: 2px 8px;
  font-size: 12px;
}
/* 括号（嵌套条件组） */
.cond-paren {
  display: inline-flex;
  align-items: center;
  white-space: nowrap;
  font-size: 13px;
  color: #606266;
  font-weight: 600;
}
/* 编辑器内条件块用到的条件运算符 / 值 / 逻辑符块 */
.cond-block {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 500;
  white-space: nowrap;
}
.cond-operator { background: #f5f7fa; color: #606266; border: 1px solid #e4e7ed; }
.cond-value { background: #fdf6ec; color: #e6a23c; border: 1px solid #faecd8; font-family: Consolas, Monaco, monospace; }

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
.tag-purple { background: #f5f0ff; color: #8e5cf7; border: 1px solid #e4d8ff; }

/* ===== 行内文本按钮 ===== */
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
.btn-text.warning { color: #e6a23c; }
.btn-text.warning:hover { background: #fdf6ec; }
.btn-text.danger { color: #f56c6c; }
.btn-text.danger:hover { background: #fef0f0; }

/* ===== 操作栏：三个按钮强制单行，溢出交给表格横向滚动 ===== */
.table-actions {
  display: flex;
  align-items: center;
  flex-wrap: nowrap; /* 不允许换行 */
  gap: 2px;
  white-space: nowrap;
  min-width: max-content; /* 内容多大占多大，不被压缩换行 */
}
.table-actions .btn-text {
  flex-shrink: 0; /* 按钮不收缩，保持完整文字 */
  white-space: nowrap;
}

.mono { font-family: Consolas, Monaco, monospace; }
.cmd { font-size: 11px; color: #606266; max-width: 220px; }

/* ===== 表格补充：规则描述 / 超时重试 ===== */
.rule-desc {
  font-size: 11px;
  color: #909399;
  margin-top: 2px;
  font-weight: 400;
}
.time-cell {
  font-size: 11px;
  color: #606266;
  line-height: 1.6;
  white-space: nowrap;
}
.time-cell span {
  display: block;
}

/* ===== 空状态 ===== */
.empty-state { text-align: center; padding: 50px 20px; }
.empty-state svg { width: 64px; height: 64px; fill: var(--text-disabled); margin-bottom: 16px; }
.empty-state p { font-size: 14px; color: var(--text-secondary); }

/* ===== 可视化规则编辑器（页面下方区域） ===== */
.editor-section {
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: 10px;
  padding: 20px;
  margin-top: 20px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06);
}
.editor-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 18px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--card-border);
}
.editor-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
  display: flex;
  align-items: center;
  gap: 8px;
}
.editor-title::before {
  content: '';
  width: 3px;
  height: 16px;
  background: #8e5cf7;
  border-radius: 2px;
}
.editor-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 20px;
  padding-top: 20px;
  border-top: 1px solid var(--card-border);
}
.editor-body { padding: 0 4px; }
.editor-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}
.editor-panel {
  border: 1px solid var(--card-border);
  border-radius: 8px;
  padding: 18px;
}
.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 6px;
}
.panel-subtitle {
  font-size: 12px;
  color: var(--text-secondary);
  margin-bottom: 14px;
}

/* ===== 条件放置区 ===== */
.drop-zone {
  min-height: 60px;
  border: 2px dashed var(--input-border);
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 14px;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  flex-wrap: wrap;
  gap: 6px;
  transition: all 0.2s;
}
.drop-zone.has-items { border-style: solid; }
.drop-placeholder {
  width: 100%;
  text-align: center;
  color: #c0c4cc;
  font-size: 13px;
}
.drag-block {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  border-radius: 6px;
  font-size: 12px;
  transition: all 0.2s;
}
.drag-handle {
  color: #c0c4cc;
  font-size: 14px;
  cursor: grab;
  user-select: none;
}
.drag-block.source { background: #ecf5ff; border: 1px solid #d9ecff; }
.drag-block.operator { background: #f5f7fa; border: 1px solid #e4e7ed; }
.drag-block.value { background: #fdf6ec; border: 1px solid #faecd8; }
.drag-block.action { background: #f0f9eb; border: 1px solid #e1f3d8; }

/* 条件块内联选择 / 输入 */
.cond-select {
  border: none;
  background: transparent;
  color: inherit;
  font-size: 12px;
  font-family: inherit;
  outline: none;
  cursor: pointer;
}
.cond-input {
  border: none;
  background: transparent;
  color: inherit;
  font-size: 12px;
  font-family: inherit;
  outline: none;
  width: 90px;
}
/* 单位后缀：指标类条件值后显示（如 % / KB/s） */
.cond-unit {
  font-size: 11px;
  color: #e6a23c;
  font-weight: 600;
  margin-left: 2px;
  white-space: nowrap;
}
.cond-remove {
  border: none;
  background: transparent;
  color: #c0c4cc;
  cursor: pointer;
  font-size: 14px;
  line-height: 1;
  padding: 0 2px;
}
.cond-remove:hover { color: #f56c6c; }

/* 条件池 */
.cond-pool-title {
  font-size: 12px;
  color: #909399;
  margin-bottom: 8px;
}
.cond-pool {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.pool-item {
  cursor: pointer;
  margin-bottom: 0;
}
.pool-item:hover { box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08); }
.pool-item.disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

/* ===== 表单 ===== */
.form-row { margin-bottom: 14px; }
.form-row label {
  display: block;
  font-size: 12px;
  color: #909399;
  margin-bottom: 6px;
}
.form-inline {
  display: flex;
  gap: 14px;
}
.form-inline .form-item { flex: 1; }
.form-item { margin-bottom: 14px; }
.form-item label {
  display: block;
  font-size: 12px;
  color: #909399;
  margin-bottom: 6px;
}
.form-row:last-child,
.form-item:last-child { margin-bottom: 0; }
.form-row .form-item:last-child { margin-bottom: 0; }
.required { color: #f56c6c; }
.form-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
  background: #f5f7fa;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 8px 12px;
}
.form-input {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid var(--input-border);
  border-radius: 6px;
  font-size: 13px;
  color: var(--text-primary);
  outline: none;
  background: var(--input-bg);
  font-family: inherit;
  transition: border-color 0.2s;
}
.form-input:focus { border-color: #409eff; }
.form-select {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid var(--input-border);
  border-radius: 6px;
  font-size: 13px;
  color: var(--text-primary);
  outline: none;
  background: var(--input-bg);
  font-family: inherit;
}

/* ===== 弹窗通用 ===== */
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 40px 20px;
  z-index: 1000;
  overflow-y: auto;
}
.modal-box {
  background: var(--card-bg);
  border-radius: 12px;
  width: 520px;
  max-width: 96vw;
  overflow: hidden;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.2);
}
.confirm-box { width: 440px; }
.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--card-border);
}
.modal-header h3 { font-size: 16px; font-weight: 600; color: var(--text-primary); }
.modal-close {
  border: none;
  background: none;
  cursor: pointer;
  color: #909399;
  padding: 4px;
  display: flex;
  align-items: center;
}
.modal-close svg { width: 18px; height: 18px; fill: currentColor; }
.modal-close:hover { color: #f56c6c; }
.modal-body { padding: 20px; }
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 16px 20px;
  border-top: 1px solid var(--card-border);
}

/* ===== 响应式 ===== */
@media (max-width: 900px) {
  .editor-grid { grid-template-columns: 1fr; }
}
</style>
