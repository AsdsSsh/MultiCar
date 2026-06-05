import { ref, onBeforeUnmount } from 'vue'
import { WS_URL } from '../utils/constants.js'

// ============================================================
// WebSocket 连接管理
// 功能：自动重连（指数退避）+ 心跳维持 + 命令发送
// 对应方案设计文档「七、WebSocket连接管理」
// ============================================================
export function useWebSocket(store, url = WS_URL) {
  const connected = ref(false)

  let ws = null
  let reconnectTimer = null
  let heartbeatTimer = null
  let reconnectDelay = 1000 // 初始 1s
  const MAX_DELAY = 30000 // 最大 30s
  const HEARTBEAT_INTERVAL = 30000 // 心跳 30s
  let manualClose = false

  function connect() {
    try {
      ws = new WebSocket(url)
    } catch (e) {
      console.error('[WS] 连接创建失败：', e)
      scheduleReconnect()
      return
    }

    ws.onopen = () => {
      connected.value = true
      store.setConnected(true)
      reconnectDelay = 1000 // 成功后重置退避
      startHeartbeat()
      console.info('[WS] 已连接：', url)
    }

    ws.onmessage = (ev) => {
      let msg
      try {
        msg = JSON.parse(ev.data)
      } catch {
        return // 非 JSON（如 pong 帧）忽略
      }
      if (msg && msg.type === 'STATE_UPDATE') {
        store.handleStateUpdate(msg)
      }
    }

    ws.onclose = () => {
      connected.value = false
      store.setConnected(false)
      stopHeartbeat()
      if (!manualClose) scheduleReconnect()
    }

    ws.onerror = () => {
      // 触发 onclose 走重连流程
      if (ws) ws.close()
    }
  }

  /** 指数退避重连：1s → 2s → 4s → 8s → … → 30s */
  function scheduleReconnect() {
    clearTimeout(reconnectTimer)
    const delay = reconnectDelay
    reconnectTimer = setTimeout(() => {
      console.info('[WS] 尝试重连…')
      connect()
    }, delay)
    reconnectDelay = Math.min(reconnectDelay * 2, MAX_DELAY)
  }

  function startHeartbeat() {
    stopHeartbeat()
    heartbeatTimer = setInterval(() => {
      if (ws && ws.readyState === WebSocket.OPEN) {
        // 浏览器 WebSocket API 无法直接发协议层 ping，使用应用层心跳
        ws.send(JSON.stringify({ cmd: 'PING', data: {}, timestamp: Date.now() }))
      }
    }, HEARTBEAT_INTERVAL)
  }

  function stopHeartbeat() {
    if (heartbeatTimer) {
      clearInterval(heartbeatTimer)
      heartbeatTimer = null
    }
  }

  /** 发送命令到 WSB（统一 JSON：{cmd,data,timestamp}） */
  function sendCommand(cmd, data = {}) {
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      console.warn('[WS] 未连接，命令已丢弃：', cmd)
      return false
    }
    ws.send(JSON.stringify({ cmd, data, timestamp: Date.now() }))
    return true
  }

  function close() {
    manualClose = true
    clearTimeout(reconnectTimer)
    stopHeartbeat()
    if (ws) ws.close()
  }

  connect()
  onBeforeUnmount(close)

  return { connected, sendCommand }
}
