import { defineStore } from 'pinia'
import { DEFAULT_CONFIG } from '../utils/constants.js'

// ============================================================
// 全局仿真状态（Pinia）
// 数据来源：WSB 推送的 STATE_UPDATE 快照
// 对应方案设计文档「六、状态管理」
// ============================================================
export const useSimulationStore = defineStore('simulation', {
  state: () => ({
    connected: false, // WebSocket 连接状态
    tick: 0, // 当前节拍号
    isRunning: false, // 仿真是否运行中（由用户操作控制，不自动推断）
    config: { ...DEFAULT_CONFIG }, // 仿真配置
    mapView: [], // 二维数组 mapHeight × mapWidth（0未探索/1已探索）
    mapBlock: [], // 二维数组 mapHeight × mapWidth（0空/1障碍）
    cars: [], // CarStateDTO[]
    hoveredCell: null, // {x, y} | null —— Canvas 悬停格点
    activeCarId: null // 当前联动高亮的小车 ID | null
  }),

  getters: {
    /** 按 ID 查找小车 */
    carById: (state) => (carId) => state.cars.find((c) => c.carId === carId) || null,

    /** 返回指定格点的综合信息 */
    cellInfo: (state) => (x, y) => ({
      explored: state.mapView?.[y]?.[x] === 1,
      blocked: state.mapBlock?.[y]?.[x] === 1,
      car: state.cars.find((c) => c.x === x && c.y === y) || null
    })
  },

  actions: {
    /** 处理 WSB 推送的 STATE_UPDATE 快照 */
    handleStateUpdate(payload) {
      if (!payload) return
      if (typeof payload.tick === 'number') this.tick = payload.tick
      if (payload.config) this.config = { ...this.config, ...payload.config }
      if (Array.isArray(payload.mapView)) this.mapView = payload.mapView
      if (Array.isArray(payload.mapBlock)) this.mapBlock = payload.mapBlock
      if (Array.isArray(payload.cars)) this.cars = payload.cars
    },

    setConnected(v) {
      this.connected = !!v
    },

    setRunning(v) {
      this.isRunning = !!v
    },

    setHoveredCell(cell) {
      this.hoveredCell = cell
    },

    setActiveCar(carId) {
      this.activeCarId = carId
    }
  }
})
