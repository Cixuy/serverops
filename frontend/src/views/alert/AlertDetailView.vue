<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getAlertEvents, getAlertEventDetail, alertAction } from '@/api/alert'
// AI 告警分析接口：手动触发分析（幂等）、查询结果、回写用户反馈
import { analyzeAlert, getAlertAiAnalysis, submitAiFeedback } from '@/api/aiAnalysis'
import { ElMessage } from '@/utils/message'
import AppSpinner from '@/components/common/AppSpinner.vue'

/*
 * ============================================================
 * 告警详情页（原型：8_alert-detail.html）
 * - 路由支持可选参数 /alerts/detail/:id?：带 id 展示指定告警；
 *   无 id（从侧边栏进入）时自动加载最新一条告警。
 * - 数据来源：GET /alert-events/{id}（alertInfo + actionLogs）。
 * - 原型的「自愈前后资源对比」「日志分析」区块后端暂无对应
 *   接口，不展示；时间线/自愈操作记录由 actionLogs 驱动。
 * ============================================================
 */

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const acting = ref(false)
const detail = ref(null)

/* ===== 枚举与展示映射 ===== */
const LEVEL_MAP = {
  CRITICAL: { label: '严重', cls: 'danger', dot: 'red' },
  WARNING: { label: '警告', cls: 'warning', dot: 'orange' },
  INFO: { label: '信息', cls: 'info', dot: 'blue' },
}
const STATUS_MAP = {
  PENDING: { label: '未处理', cls: 'warning' },
  HEALING: { label: '自愈中', cls: 'info' },
  RESOLVED: { label: '已处理', cls: 'success' },
  IGNORED: { label: '已忽略', cls: 'gray' },
}
const ACTION_MAP = {
  AUTO_HEAL: { label: '自动自愈', cls: 'warning', dot: 'blue' },
  MANUAL_RECOVER: { label: '手动恢复', cls: 'success', dot: 'green' },
  IGNORE: { label: '忽略', cls: 'gray', dot: 'orange' },
  SYSTEM_RESOLVE: { label: '系统恢复', cls: 'info', dot: 'green' },
}

function mapInfo(map, value) {
  // 未知枚举兜底：直接展示原始值，避免页面空白
  return map[value] || { label: value || '-', cls: 'gray', dot: 'blue' }
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

/* ===== 数据加载 ===== */
async function loadDetail() {
  loading.value = true
  try {
    let id = route.params.id
    // 无 id（从侧边栏进入）时加载最新一条告警，保证页面有内容
    if (!id) {
      const data = await getAlertEvents({ pageNum: 1, pageSize: 1 })
      const first = data?.list?.[0]
      if (!first) {
        detail.value = null
        return
      }
      id = first.id
    }
    const data = await getAlertEventDetail(id)
    detail.value = data
  } catch {
    detail.value = null // 查询失败展示空态，拦截器已提示
  } finally {
    loading.value = false
  }
}

// 路由 id 变化（如从列表页跳转不同告警）时重新加载
watch(() => route.params.id, loadDetail)
onMounted(loadDetail)

/* ===== 派生数据 ===== */
const alertInfo = computed(() => detail.value?.alertInfo || null)
const actionLogs = computed(() => detail.value?.actionLogs || [])

// 是否展示处置操作按钮（未处理/自愈中可操作，已处理/已忽略不可）
const operable = computed(() => {
  const s = alertInfo.value?.alertStatus
  return s === 'PENDING' || s === 'HEALING'
})

// 故障时间线：告警触发 + 各操作日志 + 服务恢复
const timeline = computed(() => {
  const a = alertInfo.value
  if (!a) return []
  const nodes = []
  // T0 告警触发
  const lv = mapInfo(LEVEL_MAP, a.alertLevel)
  nodes.push({
    time: a.triggerTime || a.createdAt,
    dot: lv.dot,
    title: `告警触发 · ${lv.label}`,
    text: fmtContent(a.alertContent),
  })
  // 处置操作记录
  actionLogs.value.forEach((log) => {
    const at = mapInfo(ACTION_MAP, log.actionType)
    const result =
      log.actionResult === 'SUCCESS' ? '成功' : log.actionResult === 'FAILED' ? '失败' : ''
    nodes.push({
      time: log.createdAt,
      dot: at.dot,
      title: `${at.label}${result ? ` · ${result}` : ''}${log.operatorName ? ` · ${log.operatorName}` : ''}`,
      text: log.actionDetail || '无详细说明',
    })
  })
  // 已恢复节点
  if (a.alertStatus === 'RESOLVED' && a.resolveTime) {
    nodes.push({
      time: a.resolveTime,
      dot: 'green',
      title: '服务恢复 · 告警解除',
      text: '服务已恢复正常，告警自动解除',
    })
  }
  return nodes
})

// 自愈操作记录（仅 AUTO_HEAL 类型）
const healingLogs = computed(() =>
  actionLogs.value.filter((l) => l.actionType === 'AUTO_HEAL')
)

/* ===== 处置操作 ===== */
async function doAction(actionType) {
  const a = alertInfo.value
  if (!a || acting.value) return
  acting.value = true
  try {
    await alertAction(a.id, { actionType })
    const tip = {
      AUTO_HEAL: '已触发自愈',
      MANUAL_RECOVER: '告警已标记为已处理',
      IGNORE: '告警已忽略',
    }[actionType]
    ElMessage.success(tip)
    await loadDetail()
  } catch {
    /* 拦截器已提示 */
  } finally {
    acting.value = false
  }
}

/* ===== AI 智能分析（新增模块，接口见 api/aiAnalysis.js） ===== */
// 反馈选项枚举（对齐后端 FeedbackRequest.userFeedback）
const FEEDBACK_OPTS = [
  { value: 'ACCURATE', label: '准确' },
  { value: 'INACCURATE', label: '不准确' },
  { value: 'PARTIAL', label: '部分准确' },
]

const aiQuerying = ref(false) // 查询已有分析结果中
const aiAnalyzing = ref(false) // 手动触发分析中
const aiResult = ref(null) // 分析结果（AiAnalysisResultVO，无结果时为 null）
const feedback = ref({ userFeedback: '', feedbackComment: '' }) // 用户反馈表单
const feedbackSubmitting = ref(false) // 反馈提交中

// 严重程度 -> 标签样式（LOW/MEDIUM/HIGH/CRITICAL）
function severityCls(v) {
  return (
    {
      LOW: 'info',
      MEDIUM: 'warning',
      HIGH: 'danger',
      CRITICAL: 'danger',
    }[v] || 'gray'
  )
}

// 置信度：后端返回 0~100 数值，非法值兜底为 0
function fmtConfidence(v) {
  const n = Number(v)
  return Number.isFinite(n) ? Math.round(n) : 0
}

// 已反馈的展示文案
function feedbackLabel(v) {
  return FEEDBACK_OPTS.find((o) => o.value === v)?.label || v || '-'
}

// 反馈标签配色：准确=绿 / 部分准确=橙 / 不准确=红，负面反馈不用绿色
function feedbackCls(v) {
  return (
    {
      ACCURATE: 'fb-tag-success',
      PARTIAL: 'fb-tag-warning',
      INACCURATE: 'fb-tag-danger',
    }[v] || 'fb-tag-success'
  )
}

// 应用分析结果
// 说明：反馈为一次性提交，提交后不再允许修改。因此已有反馈时表单保持空态，
// 反馈区整体切换为只读展示；无反馈时才显示可提交的表单。
function applyAiResult(result) {
  aiResult.value = result
  // 无论是否有历史反馈，表单都保持空态（已反馈时表单不展示，无需回填）
  feedback.value = { userFeedback: '', feedbackComment: '' }
}

// 查询当前告警的 AI 分析结果（每个告警仅一条，无结果则返回 null）
async function loadAiAnalysis() {
  const alertId = alertInfo.value?.id
  if (!alertId) {
    aiResult.value = null
    return
  }
  aiQuerying.value = true
  try {
    applyAiResult(await getAlertAiAnalysis(alertId))
  } catch {
    aiResult.value = null // 查询失败按未分析处理，拦截器已提示
  } finally {
    aiQuerying.value = false
  }
}

// 手动触发 AI 分析（幂等：已分析过的告警后端直接返回已有结果，不重复调用 LLM）
async function doAnalyze() {
  const alertId = alertInfo.value?.id
  if (!alertId || aiAnalyzing.value) return
  aiAnalyzing.value = true
  try {
    applyAiResult(await analyzeAlert(alertId))
    ElMessage.success('AI 分析完成')
  } catch {
    /* 拦截器已提示 */
  } finally {
    aiAnalyzing.value = false
  }
}

// 回写用户反馈（提交后刷新结果，applyAiResult 会把新反馈回填到表单）
async function submitFeedback() {
  const r = aiResult.value
  const f = feedback.value
  if (!r?.id || !f.userFeedback || feedbackSubmitting.value) return
  feedbackSubmitting.value = true
  try {
    await submitAiFeedback(r.id, {
      userFeedback: f.userFeedback,
      feedbackComment: f.feedbackComment,
    })
    ElMessage.success('反馈已提交')
    await loadAiAnalysis()
  } catch {
    /* 拦截器已提示 */
  } finally {
    feedbackSubmitting.value = false
  }
}

// 切换告警时重置并重新加载 AI 分析结果
watch(
  () => alertInfo.value?.id,
  () => {
    aiResult.value = null
    feedback.value = { userFeedback: '', feedbackComment: '' }
    loadAiAnalysis()
  }
)
</script>

<template>
  <div class="alert-detail">
    <!-- 返回列表 -->
    <a class="back-link" @click="router.push('/alerts')">
      <svg viewBox="0 0 24 24"><path d="M15.41 7.41L14 6l-6 6 6 6 1.41-1.41L10.83 12z" /></svg>
      返回告警列表
    </a>

    <!-- 加载中 -->
    <div v-if="loading" class="page-loading">
      <AppSpinner :size="26" />
      <span>加载中...</span>
    </div>

    <!-- 空态 -->
    <div v-else-if="!alertInfo" class="empty-card">
      <svg viewBox="0 0 24 24"><path d="M11 7h2v2h-2V7zm0 4h2v6h-2v-6zm1-9C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18a8 8 0 110-16 8 8 0 010 16z" /></svg>
      <p>未找到告警信息或数据已被清理</p>
      <button class="btn btn-default" @click="router.push('/alerts')">返回告警列表</button>
    </div>

    <template v-else>
      <!-- 告警信息卡片 -->
      <div class="alert-header-card" :class="'border-' + mapInfo(LEVEL_MAP, alertInfo.alertLevel).cls">
        <div class="alert-header-top">
          <div class="alert-title">
            <span class="tag" :class="'tag-' + mapInfo(LEVEL_MAP, alertInfo.alertLevel).cls">
              {{ alertInfo.alertLevel }}
            </span>
            <h2>{{ alertInfo.serverCode || '-' }}<template v-if="alertInfo.serviceName"> / {{ alertInfo.serviceName }} 服务异常</template></h2>
          </div>
          <div v-if="operable" class="alert-actions">
            <button class="btn btn-primary" :disabled="acting" @click="doAction('AUTO_HEAL')">
              <svg viewBox="0 0 24 24"><path d="M17.65 6.35A7.958 7.958 0 0012 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0112 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z" /></svg>
              触发自愈
            </button>
            <button class="btn btn-success" :disabled="acting" @click="doAction('MANUAL_RECOVER')">
              <svg viewBox="0 0 24 24"><path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z" /></svg>
              标记已处理
            </button>
            <button class="btn btn-default" :disabled="acting" @click="doAction('IGNORE')">
              <svg viewBox="0 0 24 24"><path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z" /></svg>
              忽略
            </button>
          </div>
          <span v-else class="status-text">
            <span class="tag" :class="'tag-' + mapInfo(STATUS_MAP, alertInfo.alertStatus).cls">
              {{ mapInfo(STATUS_MAP, alertInfo.alertStatus).label }}
            </span>
          </span>
        </div>
        <div class="alert-info-grid">
          <div class="alert-info-item">
            <label>告警编号</label>
            <span>{{ alertInfo.alertNo || `#${alertInfo.id}` }}</span>
          </div>
          <div class="alert-info-item">
            <label>服务器</label>
            <span>{{ alertInfo.serverCode || '-' }}</span>
          </div>
          <div class="alert-info-item">
            <label>触发时间</label>
            <span>{{ fmtTime(alertInfo.triggerTime || alertInfo.createdAt) }}</span>
          </div>
          <div class="alert-info-item">
            <label>告警类型</label>
            <span>{{ alertInfo.alertType || '-' }}</span>
          </div>
          <div class="alert-info-item">
            <label>服务名称</label>
            <span>{{ alertInfo.serviceName || '-' }}</span>
          </div>
          <div class="alert-info-item">
            <label>当前状态</label>
            <span class="tag" :class="'tag-' + mapInfo(STATUS_MAP, alertInfo.alertStatus).cls">
              {{ mapInfo(STATUS_MAP, alertInfo.alertStatus).label }}
            </span>
          </div>
        </div>
      </div>

      <!-- AI 智能分析（新增模块：结果查询/手动触发/处置建议/用户反馈） -->
      <div class="ai-section">
        <div class="section-title">AI 智能分析</div>

        <!-- 查询已有结果中 -->
        <div v-if="aiQuerying" class="ai-state">
          <AppSpinner :size="18" />
          <span>正在查询分析结果...</span>
        </div>

        <!-- 未分析：引导手动触发（幂等，重复触发直接返回已有结果） -->
        <div v-else-if="!aiResult" class="ai-empty">
          <svg viewBox="0 0 24 24"><path d="M9.4 16.6L4.8 12l4.6-4.6L8 6l-6 6 6 6 1.4-1.4zm5.2 0l4.6-4.6-4.6-4.6L16 6l6 6-6 6-1.4-1.4zM13.5 5L11 19h2l2.5-14h-2z" /></svg>
          <p>该告警尚未进行 AI 分析。将由本地大模型结合告警上下文自动定位根因并给出处置建议，每个告警仅分析一次。</p>
          <button class="btn btn-primary" :disabled="aiAnalyzing" @click="doAnalyze">
            <AppSpinner v-if="aiAnalyzing" :size="14" variant="white" />
            {{ aiAnalyzing ? '分析中...' : '开始 AI 分析' }}
          </button>
        </div>

        <!-- 已分析：结果展示 -->
        <div v-else class="ai-result">
          <!-- 根因总结 + 严重程度 + 置信度 -->
          <div class="ai-overview">
            <div class="ai-summary">
              <label>根因总结</label>
              <div class="ai-summary-text">{{ aiResult.rootCauseSummary || '暂无根因描述' }}</div>
            </div>
            <div class="ai-meta">
              <div class="ai-meta-item">
                <label>严重程度</label>
                <span class="tag" :class="'tag-' + severityCls(aiResult.severityAssessment)">
                  {{ aiResult.severityAssessment || '-' }}
                </span>
              </div>
              <div class="ai-meta-item">
                <label>置信度</label>
                <span class="confidence-num">{{ fmtConfidence(aiResult.confidenceScore) }}%</span>
              </div>
            </div>
          </div>

          <!-- 处置建议列表 -->
          <div v-if="aiResult.recommendations?.length" class="ai-block">
            <div class="ai-block-title">处置建议</div>
            <div v-for="rec in aiResult.recommendations" :key="rec.priority" class="rec-item">
              <div class="rec-head">
                <span class="rec-priority">P{{ rec.priority }}</span>
                <span class="rec-title">{{ rec.title }}</span>
              </div>
              <ol class="rec-steps">
                <li v-for="(step, si) in rec.steps" :key="si">{{ step }}</li>
              </ol>
              <div v-if="rec.expectedEffect" class="rec-effect">预期效果：{{ rec.expectedEffect }}</div>
              <div v-if="rec.command" class="rec-cmd">建议命令：<code>{{ rec.command }}</code></div>
            </div>
          </div>

          <!-- 分析结论全文 -->
          <div v-if="aiResult.analysisResult" class="ai-block">
            <div class="ai-block-title">分析结论</div>
            <pre class="ai-pre">{{ aiResult.analysisResult }}</pre>
          </div>

          <!-- 用户反馈（一次性提交：已反馈后只读展示，不再提供表单） -->
          <div class="ai-block ai-feedback">
            <div class="ai-block-title">结果反馈</div>
            <!-- 已反馈：只读展示上次的反馈内容 -->
            <div v-if="aiResult.userFeedback" class="fb-done">
              <span class="fb-done-tag" :class="feedbackCls(aiResult.userFeedback)">{{ feedbackLabel(aiResult.userFeedback) }}</span>
              <span v-if="aiResult.feedbackComment" class="fb-done-comment">{{ aiResult.feedbackComment }}</span>
            </div>
            <!-- 未反馈：展示提交表单 -->
            <template v-else>
              <div class="fb-options">
                <button
                  v-for="opt in FEEDBACK_OPTS"
                  :key="opt.value"
                  class="fb-btn"
                  :class="{ active: feedback.userFeedback === opt.value }"
                  @click="feedback.userFeedback = opt.value"
                >{{ opt.label }}</button>
              </div>
              <textarea
                v-model="feedback.feedbackComment"
                class="fb-input"
                rows="2"
                placeholder="反馈备注（可选），如：根因定位准确，但建议步骤顺序可优化"
              ></textarea>
              <div class="fb-actions">
                <button
                  class="btn btn-default"
                  :disabled="feedbackSubmitting || !feedback.userFeedback"
                  @click="submitFeedback"
                >{{ feedbackSubmitting ? '提交中...' : '提交反馈' }}</button>
              </div>
            </template>
          </div>
        </div>
      </div>

      <!-- 故障时间线 -->
      <div class="timeline-section">
        <div class="section-title">故障时间线回放</div>
        <div v-if="timeline.length" class="timeline">
          <div v-for="(node, i) in timeline" :key="i" class="tl-item">
            <div class="tl-dot" :class="node.dot">
              <svg viewBox="0 0 24 24">
                <path v-if="i === 0" d="M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z" />
                <path v-else-if="node.dot === 'green'" d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z" />
                <path v-else d="M12 22a2.5 2.5 0 002-4H10a2.5 2.5 0 002 4zm6-6V11a6 6 0 10-12 0v5l-2 2v1h16v-1l-2-2z" />
              </svg>
            </div>
            <div class="tl-content" :class="node.dot">
              <div class="tl-header">
                <span class="tl-step" :class="node.dot">T{{ i }} · {{ node.title }}</span>
                <span class="tl-time">{{ fmtTime(node.time) }}</span>
              </div>
              <div class="tl-body">{{ node.text }}</div>
            </div>
          </div>
        </div>
        <div v-else class="dim empty-tip">暂无时间线数据</div>
      </div>

      <!-- 自愈操作记录 -->
      <div class="action-section">
        <div class="section-title">自愈操作记录</div>
        <div v-if="healingLogs.length" class="action-list">
          <div
            v-for="log in healingLogs"
            :key="log.id"
            class="action-card"
            :class="{ failed: log.actionResult === 'FAILED' }"
          >
            <div class="action-header">
              <span class="action-title" :class="{ failed: log.actionResult === 'FAILED' }">
                {{ log.actionResult === 'FAILED' ? '✕' : '✓' }} {{ mapInfo(ACTION_MAP, log.actionType).label }}
              </span>
              <span class="action-time">
                {{ fmtTime(log.createdAt) }}<template v-if="log.operatorName"> · {{ log.operatorName }}</template>
              </span>
            </div>
            <div class="action-body">{{ log.actionDetail || '无详细说明' }}</div>
          </div>
        </div>
        <div v-else class="dim empty-tip">该告警暂无自愈操作记录</div>
      </div>
    </template>
  </div>
</template>

<style scoped>
/* 返回链接 */
.back-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: #409eff;
  text-decoration: none;
  margin-bottom: 12px;
  cursor: pointer;
  background: none;
  border: none;
  font-family: inherit;
}
.back-link:hover { text-decoration: underline; }
.back-link svg { width: 14px; height: 14px; fill: #409eff; }

.page-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 80px 0;
  color: #409eff;
  font-size: 13px;
}

/* 空态 */
.empty-card {
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: 10px;
  padding: 60px 20px;
  text-align: center;
}
.empty-card svg { width: 64px; height: 64px; fill: var(--text-disabled); margin-bottom: 16px; }
.empty-card p { font-size: 14px; color: var(--text-secondary); margin-bottom: 16px; }

/* 按钮 */
.btn {
  padding: 8px 16px;
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
.btn-primary:hover:not(:disabled) { background: #66b1ff; }
.btn-success { background: #67c23a; border-color: #67c23a; color: #fff; }
.btn-success:hover:not(:disabled) { background: #85ce61; }
.btn-default { background: var(--card-bg); border-color: var(--input-border); color: var(--text-regular); }
.btn-default:hover:not(:disabled) { color: #409eff; border-color: #c6e2ff; background: #ecf5ff; }
.btn:disabled { opacity: 0.6; cursor: not-allowed; }
.btn svg { width: 14px; height: 14px; fill: currentColor; }

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

/* 告警信息卡片（左侧色条随级别变化） */
.alert-header-card {
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: 10px;
  padding: 24px;
  margin-bottom: 20px;
  border-left-width: 4px;
  border-left-style: solid;
}
.border-danger { border-left-color: #f56c6c; }
.border-warning { border-left-color: #e6a23c; }
.border-info { border-left-color: #409eff; }
.alert-header-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
  flex-wrap: wrap;
}
.alert-title { display: flex; align-items: center; gap: 12px; min-width: 0; }
.alert-title h2 {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.alert-actions { display: flex; gap: 8px; flex-shrink: 0; }
.status-text { flex-shrink: 0; }

.alert-info-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px 24px;
}
.alert-info-item label {
  display: block;
  font-size: 12px;
  color: var(--text-secondary);
  margin-bottom: 4px;
}
.alert-info-item span { font-size: 14px; color: var(--text-primary); font-weight: 500; }

/* ===== AI 智能分析区块（新增模块样式） ===== */
.ai-section {
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: 10px;
  padding: 24px;
  margin-bottom: 20px;
}
/* 查询中状态 */
.ai-state {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 20px 0;
  color: var(--text-secondary);
  font-size: 13px;
}
/* 未分析引导态 */
.ai-empty {
  text-align: center;
  padding: 28px 20px;
  border: 1px dashed var(--card-border);
  border-radius: 8px;
  background: var(--row-hover-bg);
}
.ai-empty svg { width: 40px; height: 40px; fill: var(--text-disabled); margin-bottom: 12px; }
.ai-empty p {
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.6;
  max-width: 560px;
  margin: 0 auto 16px;
}
/* 结果概览：根因 + 严重程度 + 置信度 */
.ai-overview {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 24px;
  padding: 16px 20px;
  background: var(--row-hover-bg);
  border-radius: 8px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.ai-summary { flex: 1; min-width: 260px; }
.ai-summary label,
.ai-meta-item label {
  display: block;
  font-size: 12px;
  color: var(--text-secondary);
  margin-bottom: 6px;
}
.ai-summary-text { font-size: 14px; font-weight: 600; color: var(--text-primary); line-height: 1.6; }
.ai-meta { display: flex; gap: 32px; flex-shrink: 0; }
.confidence-num { font-size: 18px; font-weight: 700; color: #409eff; }
/* 通用子区块 */
.ai-block { margin-bottom: 16px; }
.ai-block:last-child { margin-bottom: 0; }
.ai-block-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 12px;
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  user-select: none;
}
.ai-block-title::before { content: ''; width: 3px; height: 13px; background: #409eff; border-radius: 2px; }
/* 处置建议条目 */
.rec-item {
  border: 1px solid var(--card-border);
  border-radius: 8px;
  padding: 14px 16px;
  margin-bottom: 12px;
  background: var(--card-bg);
}
.rec-item:last-child { margin-bottom: 0; }
.rec-head { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.rec-priority {
  flex-shrink: 0;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 600;
  color: #409eff;
  background: #ecf5ff;
  border: 1px solid #d9ecff;
}
.rec-title { font-size: 14px; font-weight: 600; color: var(--text-primary); }
.rec-steps {
  margin: 0 0 8px;
  padding-left: 20px;
  font-size: 13px;
  color: var(--text-regular);
  line-height: 1.8;
}
.rec-effect { font-size: 12px; color: #67c23a; margin-bottom: 4px; }
.rec-cmd { font-size: 12px; color: var(--text-secondary); }
.rec-cmd code {
  background: #f4f4f5;
  border: 1px solid #e9e9eb;
  border-radius: 4px;
  padding: 1px 6px;
  font-size: 12px;
  color: #e6a23c;
}
/* 分析结论 / 推理链纯文本 */
.ai-pre {
  background: var(--row-hover-bg);
  border: 1px solid var(--card-border);
  border-radius: 8px;
  padding: 14px 16px;
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-primary);
  white-space: pre-wrap;
  word-break: break-all;
  font-family: inherit;
}
/* 反馈区 */
.fb-options { display: flex; gap: 8px; margin-bottom: 12px; }
.fb-btn {
  padding: 6px 14px;
  border-radius: 6px;
  border: 1px solid var(--input-border);
  background: var(--card-bg);
  color: var(--text-regular);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
  font-family: inherit;
}
.fb-btn:hover { color: #409eff; border-color: #c6e2ff; }
.fb-btn.active { color: #fff; background: #409eff; border-color: #409eff; }
.fb-input {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid var(--input-border);
  border-radius: 6px;
  padding: 8px 10px;
  font-size: 13px;
  color: var(--text-primary);
  background: var(--card-bg);
  resize: vertical;
  font-family: inherit;
  margin-bottom: 12px;
}
.fb-input:focus { outline: none; border-color: #409eff; }
.fb-actions { display: flex; align-items: center; justify-content: flex-end; gap: 12px; }
/* 已反馈的只读展示：标签 + 备注 */
.fb-done {
  display: flex;
  align-items: center;
  gap: 8px;
  background: #f0f9eb;
  border: 1px solid #e1f3d8;
  border-radius: 6px;
  padding: 10px 14px;
}
.fb-done-tag {
  flex-shrink: 0;
  padding: 2px 10px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
  background: #67c23a; /* 默认绿色，具体颜色随反馈类型通过变体 class 覆盖 */
  color: #fff;
}
/* 反馈标签颜色变体：准确=绿 / 部分准确=橙 / 不准确=红 */
.fb-tag-success { background: #67c23a; }
.fb-tag-warning { background: #e6a23c; }
.fb-tag-danger { background: #f56c6c; }
.fb-done-comment { font-size: 13px; color: var(--text-regular); }

/* 时间线 */
.timeline-section {
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: 10px;
  padding: 24px;
  margin-bottom: 20px;
}
.section-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 24px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.section-title::before { content: ''; width: 3px; height: 16px; background: #409eff; border-radius: 2px; }

.timeline { position: relative; padding-left: 40px; }
.timeline::before {
  content: '';
  position: absolute;
  left: 11px;
  top: 4px;
  bottom: 4px;
  width: 3px;
  border-radius: 2px;
  background: linear-gradient(to bottom, #f56c6c 0%, #e6a23c 40%, #409eff 70%, #67c23a 100%);
}
.tl-item { position: relative; margin-bottom: 28px; }
.tl-item:last-child { margin-bottom: 0; }
.tl-dot {
  position: absolute;
  left: -32px;
  top: 4px;
  width: 19px;
  height: 19px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
}
.tl-dot.red { background: #f56c6c; box-shadow: 0 0 0 4px rgba(245, 108, 108, 0.15); }
.tl-dot.orange { background: #e6a23c; box-shadow: 0 0 0 4px rgba(230, 162, 60, 0.15); }
.tl-dot.blue { background: #409eff; box-shadow: 0 0 0 4px rgba(64, 158, 255, 0.15); }
.tl-dot.green { background: #67c23a; box-shadow: 0 0 0 4px rgba(103, 194, 58, 0.15); }
.tl-dot svg { width: 10px; height: 10px; fill: #fff; }
.tl-content {
  background: var(--row-hover-bg);
  border-radius: 8px;
  padding: 14px 16px;
  border-left: 3px solid;
}
.tl-content.red { border-left-color: #f56c6c; }
.tl-content.orange { border-left-color: #e6a23c; }
.tl-content.blue { border-left-color: #409eff; }
.tl-content.green { border-left-color: #67c23a; }
.tl-header { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 6px; }
.tl-step { font-size: 12px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.5px; }
.tl-step.red { color: #f56c6c; }
.tl-step.orange { color: #e6a23c; }
.tl-step.blue { color: #409eff; }
.tl-step.green { color: #67c23a; }
.tl-time { font-size: 12px; color: var(--text-secondary); white-space: nowrap; }
.tl-body { font-size: 13px; color: var(--text-primary); line-height: 1.6; word-break: break-all; }

/* 自愈操作记录 */
.action-section {
  background: var(--card-bg);
  border: 1px solid var(--card-border);
  border-radius: 10px;
  padding: 24px;
}
.action-card {
  background: #f0f9eb;
  border: 1px solid #e1f3d8;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 12px;
}
.action-card:last-child { margin-bottom: 0; }
.action-card.failed { background: #fef0f0; border-color: #fde2e2; }
.action-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 8px; }
.action-title { font-size: 14px; font-weight: 600; color: #67c23a; }
.action-title.failed { color: #f56c6c; }
.action-time { font-size: 12px; color: var(--text-secondary); white-space: nowrap; }
.action-body { font-size: 13px; color: var(--text-primary); line-height: 1.6; word-break: break-all; }

.dim { color: var(--text-regular); }
.empty-tip { padding: 8px 0; font-size: 13px; }
</style>
