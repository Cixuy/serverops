import request from './request'

/**
 * AI 告警分析 API（接口文档：默认模块 / 告警分析）
 * 核心接口：手动触发分析（幂等）、查询分析结果、回写用户反馈。
 * 统一使用 Result 返回结构（code=0/message/data），由 request 拦截器解包 data。
 *
 * 说明：
 * - 每个告警仅分析一次：首次触发调用本地 LLM 并落库 ai_analysis_result，
 *   再次触发直接返回已有结果（HTTP 200，不重复调用模型）。
 * - data 字段对齐 AiAnalysisResultVO：
 *   id / alertId / rootCauseSummary / severityAssessment / confidenceScore /
 *   recommendations[] / reasoningChain / analysisResult / userFeedback /
 *   feedbackComment / createdAt / updatedAt
 */

// 手动触发 AI 分析（幂等：已分析过则直接返回已有结果，不重复调用 LLM）
// 说明：首次触发需等待本地 LLM 推理 + 向量检索，后端最坏耗时约 120s，
// 远超全局默认超时 15s，故此处单独放宽到 5 分钟，避免请求提前中断。
export function analyzeAlert(alertId) {
  return request({
    method: 'post',
    url: `/ai-analysis/alerts/${alertId}/analyze`,
    timeout: 300000, // 5 分钟，覆盖 LLM 推理最坏耗时
  })
}

// 查询当前告警的 AI 分析结果（无分析结果则返回 null）
export function getAlertAiAnalysis(alertId) {
  return request({ method: 'get', url: `/ai-analysis/alerts/${alertId}` })
}

// 回写用户反馈（userFeedback: ACCURATE / INACCURATE / PARTIAL，feedbackComment 可选）
export function submitAiFeedback(id, payload) {
  return request({ method: 'post', url: `/ai-analysis/${id}/feedback`, data: payload })
}
