/**
 * 实时监控 WebSocket 客户端
 * 端点：/api/v1/ws/frontend
 * 通过 query 参数 SocketToken 传递登录态（与 axios 的 Authorization 头等价）
 *
 * 订阅实时指标，并对外回调分发以下消息：
 *  - server_metrics      基础指标推送（cpu/内存/网络）
 *  - server_disk_metrics 磁盘指标推送（数组）
 *  - ack                 订阅/取消订阅确认
 *  - error               错误
 *  - pong                心跳回应（忽略）
 *
 * 连接状态通过 onStatus 回调上报：'connecting' | 'open' | 'reconnecting' | 'closed'
 *
 * 用法：
 *   const ws = createMetricsWs({
 *     onServerMetrics: (msg) => {...},
 *     onDiskMetrics: (msg) => {...},
 *     onAck: (msg) => {...},
 *     onError: (msg) => {...},
 *     onStatus: (status) => {...},
 *   })
 *   ws.connect()
 *   ws.subscribe(['SRV-8F3A2B'], ['server_metrics', 'server_disk_metrics'])
 *   ws.unsubscribe(['SRV-8F3A2B'])
 *   ws.close()  // 主动彻底关闭
 */
import { API_PREFIX, STORAGE_KEYS, WS_BASE_URL } from '@/config'

// 基础路径，前端直连后端（不走 Vite 代理），前缀跟随 API_PREFIX 可配置
const WS_PATH = `${API_PREFIX}/ws/frontend`

// 心跳间隔（60s，与接口约定一致）
const HEARTBEAT_INTERVAL = 60 * 1000
// 断线后重连间隔
const RECONNECT_INTERVAL = 3000
// 最大重连次数（避免死循环，可设 Infinity 无限重连）
const MAX_RECONNECT_ATTEMPTS = 10

function uuidv4() {
  if (crypto && typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

/**
 * 创建 WS 客户端
 * @param {Object} handlers 消息回调集合
 * @returns {MetricsWsClient}
 */
export function createMetricsWs(handlers = {}) {
  return new MetricsWsClient(handlers)
}

class MetricsWsClient {
  constructor(handlers) {
    this.handlers = handlers
    this.ws = null
    this.heartbeatTimer = null
    this.reconnectTimer = null
    this.reconnectAttempts = 0
    // 主动关闭标志：为 true 时不再自动重连
    this.intentionalClose = false
    // 已订阅状态，重连后自动恢复订阅
    this.subscribed = false
    this.subServerCodes = []
    this.subMetrics = []
  }

  /** 构建带 SocketToken 的 WebSocket URL（直连后端，根据后端地址协议选择 ws/wss） */
  _buildUrl() {
    const token = localStorage.getItem(STORAGE_KEYS.TOKEN) || ''
    const base = new URL(WS_BASE_URL)
    const proto = base.protocol === 'https:' ? 'wss:' : 'ws:'
    const host = base.host // 含端口，如 localhost:8080
    const url = `${proto}//${host}${WS_PATH}?SocketToken=${encodeURIComponent(token)}`
    return url
  }

  connect() {
    if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) {
      return
    }
    this.intentionalClose = false
    this.handlers.onStatus?.('connecting')
    const url = this._buildUrl()
    this.ws = new WebSocket(url)

    this.ws.onopen = () => {
      this.reconnectAttempts = 0
      this.handlers.onStatus?.('open')
      this._startHeartbeat()
      // 重连成功后自动恢复订阅
      if (this.subscribed && this.subServerCodes.length) {
        this._sendSubscribe(this.subServerCodes, this.subMetrics)
      }
    }

    this.ws.onmessage = (evt) => {
      let msg
      try {
        msg = JSON.parse(evt.data)
      } catch {
        return
      }
      if (import.meta.env.DEV) console.debug('[WS] recv', msg.type, msg)
      this._dispatch(msg)
    }

    this.ws.onclose = () => {
      this._stopHeartbeat()
      if (!this.intentionalClose) {
        this.handlers.onStatus?.('reconnecting')
        this._scheduleReconnect()
      } else {
        this.handlers.onStatus?.('closed')
      }
    }

    this.ws.onerror = () => {
      // 错误后浏览器会触发 onclose，重连逻辑在 onclose 中处理
    }
  }

  _dispatch(msg) {
    if (!msg || !msg.type) return
    switch (msg.type) {
      case 'server_metrics':
        this.handlers.onServerMetrics?.(msg)
        break
      case 'server_disk_metrics':
        this.handlers.onDiskMetrics?.(msg)
        break
      case 'ack':
        this.handlers.onAck?.(msg)
        break
      case 'error':
        this.handlers.onError?.(msg)
        break
      case 'pong':
        // 心跳回应，忽略
        break
      default:
        break
    }
  }

  _startHeartbeat() {
    this._stopHeartbeat()
    this.heartbeatTimer = setInterval(() => {
      if (this.ws && this.ws.readyState === WebSocket.OPEN) {
        this.ws.send(JSON.stringify({ action: 'ping', timestamp: Date.now() }))
      }
    }, HEARTBEAT_INTERVAL)
  }

  _stopHeartbeat() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = null
    }
  }

  _scheduleReconnect() {
    if (this.reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) return
    this.reconnectAttempts += 1
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer)
    this.reconnectTimer = setTimeout(() => this.connect(), RECONNECT_INTERVAL)
  }

  _sendSubscribe(serverCodes, metrics) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return
    this.ws.send(
      JSON.stringify({
        action: 'subscribe',
        request_id: uuidv4(),
        payload: { server_codes: serverCodes, metrics },
      }),
    )
  }

  /** 订阅指定服务器的指标类型（幂等，重复调用会更新订阅集合） */
  subscribe(serverCodes, metrics) {
    this.subscribed = true
    this.subServerCodes = serverCodes
    this.subMetrics = metrics
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this._sendSubscribe(serverCodes, metrics)
    }
    // 若连接尚未建立，onopen 中会自动订阅
  }

  /** 取消订阅指定服务器（保留连接，便于后续重新订阅） */
  unsubscribe(serverCodes) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return
    this.ws.send(
      JSON.stringify({
        action: 'unsubscribe',
        request_id: uuidv4(),
        payload: { server_codes: serverCodes },
      }),
    )
    // 从订阅集合中移除
    this.subServerCodes = this.subServerCodes.filter((c) => !serverCodes.includes(c))
    if (this.subServerCodes.length === 0) this.subscribed = false
  }

  /** 主动彻底关闭连接，不再重连 */
  close() {
    this.intentionalClose = true
    this._stopHeartbeat()
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    if (this.ws) {
      this.ws.close()
      this.ws = null
    }
    this.subscribed = false
    this.subServerCodes = []
    this.subMetrics = []
  }
}
