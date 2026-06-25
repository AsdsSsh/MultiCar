// ============================================================
// 全局常量：颜色方案、尺寸、命令、状态、WebSocket 配置
// 对应方案设计文档「五、Canvas渲染设计 5.4」与「一、接口规范」
// ============================================================

/**
 * WebSocket 连接地址（WSB 仿真通道）
 * 分布式部署时，请将 localhost 改为 Display 服务所在机器的 IP 地址
 * 例如: 'ws://192.168.1.100:8887/ws/simulation'
 */
export const WS_URL = import.meta.env.VITE_WS_URL || 'ws://localhost:8887/ws/simulation'

/** 默认仿真配置 */
export const DEFAULT_CONFIG = {
  mapWidth: 40,
  mapHeight: 30,
  carCount: 5,
  obstacleDensity: 0.2,
  algorithm: 'A_STAR'
}

/** 前端 → WSB 命令枚举（统一 JSON：{cmd,data,timestamp}） */
export const COMMANDS = {
  SET_CONFIG: 'SET_CONFIG',
  SET_MAP_EDIT: 'SET_MAP_EDIT',
  RESET: 'RESET',
  PAUSE: 'PAUSE',
  RESUME: 'RESUME',
  STEP_ONCE: 'STEP_ONCE',
  ADD_CAR: 'ADD_CAR',
  DELETE_CAR: 'DELETE_CAR',
  MOVE_CAR: 'MOVE_CAR',
  JOIN_SESSION: 'JOIN_SESSION',
  LEAVE_SESSION: 'LEAVE_SESSION'
}

/** WebSocket 服务端推送消息类型 */
export const WS_TYPES = {
  STATE_UPDATE: 'STATE_UPDATE',
  MAP_LIST_UPDATED: 'MAP_LIST_UPDATED',
  SIMULATION_LIST_UPDATED: 'SIMULATION_LIST_UPDATED',
  SERVICE_UPDATE: 'SERVICE_UPDATE'
}

/** 路径规划算法选项 */
export const ALGORITHMS = [
  { value: 'A_STAR', label: 'A*' },
  { value: 'BFS', label: 'BFS' }
]

/** 小车状态枚举（与 Redis CarID:Status 一致） */
export const CAR_STATUS = {
  IDLE: 'IDLE',
  WAITING_ROUTE: 'WAITING_ROUTE',
  READY: 'READY',
  MOVING: 'MOVING',
  BLOCKED: 'BLOCKED'
}

/** 状态中文标签 */
export const STATUS_LABELS = {
  IDLE: '空闲',
  WAITING_ROUTE: '等待路径',
  READY: '就绪',
  MOVING: '移动中',
  BLOCKED: '受阻'
}

/** 状态指示色 */
export const STATUS_COLORS = {
  IDLE: '#9e9e9e',
  WAITING_ROUTE: '#ffd54f',
  READY: '#4fc3f7',
  MOVING: '#81c784',
  BLOCKED: '#e57373'
}

/** Canvas 颜色方案（文档 5.4） */
export const COLORS = {
  BACKGROUND: '#1a1a1a',
  UNEXPLORED: '#5a5a5a',
  EXPLORED: '#dcdcdc',
  GRID: '#555555',
  OBSTACLE: '#cc4444',
  DYNAMIC_OBSTACLE: '#882222',
  TARGET: '#ffaa00',
  HOVER: 'rgba(255, 255, 255, 0.25)',
  HUD_BG: 'rgba(0, 0, 0, 0.75)',
  HUD_TEXT: '#ffffff'
}

/** 小车配色（最多 5 辆循环复用，文档 5.4） */
export const CAR_COLORS = [
  '#4fc3f7', // 小车1
  '#ff8a65', // 小车2
  '#81c784', // 小车3
  '#ffd54f', // 小车4
  '#ba68c8' // 小车5
]

/** 路线半透明度 */
export const ROUTE_ALPHA = 0.5
