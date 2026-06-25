import { defineStore } from 'pinia'
import { DEFAULT_CONFIG } from '../utils/constants.js'
import { decompressRLE } from '../utils/mapCompression.js'

// ============================================================
// 全局仿真状态（Pinia）
// 数据来源：WSB 推送的 STATE_UPDATE 快照
// 对应方案设计文档「六、状态管理」
// ============================================================
export const useSimulationStore = defineStore('simulation', {
  state: () => ({
    sessionId: null,  // 当前仿真会话 ID
    connected: false, // WebSocket 连接状态
    tick: 0, // 当前节拍号
    isRunning: false, // 仿真是否运行中（由用户操作控制，不自动推断）
    config: { ...DEFAULT_CONFIG }, // 仿真配置
    mapView: [], // 二维数组 mapHeight × mapWidth（0未探索/1已探索）
    mapBlock: [], // 二维数组 mapHeight × mapWidth（0空/1障碍）
    cars: [], // CarStateDTO[]
    hoveredCell: null, // {x, y} | null —— Canvas 悬停格点
    activeCarId: null, // 当前联动高亮的小车 ID | null

    // ===== 地图编辑模式 =====
    editMode: false,           // 是否处于地图编辑模式
    editTool: 'obstacle',      // 'obstacle' | 'car'
    editObstacles: [],         // 编辑中的障碍物坐标 [{x, y}, ...]
    editCarPositions: [],      // 编辑中的小车初始位置 [{carId, x, y}, ...]
    editCarCount: 0,           // 编辑模式下的小车数量
    editMapWidth: 0,           // 编辑模式下的地图宽度
    editMapHeight: 0,           // 编辑模式下的地图高度

    services: {},  // { navigator: [{instanceId,host,pid,...}], ... }
    controllerId: null,  // 当前 session 所在的 Controller 实例 ID
  }),

  getters: {
    /** 按 ID 查找小车 */
    carById: (state) => (carId) => state.cars.find((c) => c.carId === carId) || null,

    /** 返回指定格点的综合信息 */
    cellInfo: (state) => (x, y) => ({
      explored: state.mapView?.[y]?.[x] === 1,
      blocked: state.mapBlock?.[y]?.[x] === 1,
      car: state.cars.find((c) => c.x === x && c.y === y) || null
    }),

    /** 编辑模式下：获取指定格点的编辑信息 */
    editCellInfo: (state) => (x, y) => {
      const isObstacle = state.editObstacles.some(p => p.x === x && p.y === y)
      const car = state.editCarPositions.find(c => c.x === x && c.y === y)
      return { isObstacle, car: car || null }
    },

    /** 编辑模式下：获取所有被保护的格子（小车周围3x3） */
    editProtectedCells: (state) => {
      const cells = new Set()
      for (const car of state.editCarPositions) {
        for (let dx = -1; dx <= 1; dx++) {
          for (let dy = -1; dy <= 1; dy++) {
            const nx = car.x + dx
            const ny = car.y + dy
            if (nx >= 0 && nx < state.editMapWidth && ny >= 0 && ny < state.editMapHeight) {
              cells.add(nx + ',' + ny)
            }
          }
        }
      }
      return cells
    }
  },

  actions: {
    /** 处理 WSB 推送的 STATE_UPDATE 快照 */
    handleStateUpdate(payload) {
      if (!payload) return
      if (payload.sessionId) this.sessionId = payload.sessionId
      if (typeof payload.tick === 'number') this.tick = payload.tick
      if (typeof payload.running === 'boolean') this.isRunning = payload.running
      if (payload.controllerId !== undefined) this.controllerId = payload.controllerId
      if (payload.config) {
        this.config = { ...this.config, ...payload.config }
        // 强制 mapWidth/mapHeight 为数字类型，防止字符串导致渲染异常
        const mw = Number(this.config.mapWidth)
        const mh = Number(this.config.mapHeight)
        if (mw > 0) this.config.mapWidth = mw
        if (mh > 0) this.config.mapHeight = mh
      }

      // 处理压缩地图数据
      if (payload.compressed) {
        const w = payload.mapWidth || this.config.mapWidth
        const h = payload.mapHeight || this.config.mapHeight
        if (Array.isArray(payload.mapViewCompressed)) {
          this.mapView = decompressRLE(payload.mapViewCompressed, w, h)
        }
        if (Array.isArray(payload.mapBlockCompressed)) {
          this.mapBlock = decompressRLE(payload.mapBlockCompressed, w, h)
        }
      } else {
        if (Array.isArray(payload.mapView)) this.mapView = payload.mapView
        if (Array.isArray(payload.mapBlock)) this.mapBlock = payload.mapBlock
      }

      if (Array.isArray(payload.cars)) {
        // 按carId排序，确保地图和右侧列表编号一致
        this.cars = [...payload.cars].sort((a, b) => {
          const na = parseInt((a.carId || '').replace(/\D/g, '')) || 0
          const nb = parseInt((b.carId || '').replace(/\D/g, '')) || 0
          return na - nb
        })
      }
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
    },

    setServices(snapshot) {
      this.services = snapshot || {}
    },

    // ===== 地图编辑模式 actions =====

    /** 进入地图编辑模式 */
    enterEditMode(config) {
      this.editMode = true
      this.editMapWidth = config.mapWidth
      this.editMapHeight = config.mapHeight
      this.editCarCount = config.carCount
      this.editTool = 'obstacle'
      // 从现有 mapBlock 初始化障碍物
      this.editObstacles = []
      if (Array.isArray(this.mapBlock) && this.mapBlock.length > 0) {
        for (let y = 0; y < this.mapBlock.length; y++) {
          for (let x = 0; x < (this.mapBlock[y]?.length || 0); x++) {
            if (this.mapBlock[y][x] === 1) {
              this.editObstacles.push({ x, y })
            }
          }
        }
      }
      // 从现有 cars 初始化小车位置
      this.editCarPositions = (this.cars || []).map((c, i) => ({
        carId: c.carId || `Car${String(i + 1).padStart(3, '0')}`,
        x: c.x ?? 0,
        y: c.y ?? 0
      }))
    },

    /** 退出地图编辑模式 */
    exitEditMode() {
      this.editMode = false
      this.editObstacles = []
      this.editCarPositions = []
      this.editCarCount = 0
      this.editMapWidth = 0
      this.editMapHeight = 0
    },

    /** 切换编辑工具 */
    setEditTool(tool) {
      this.editTool = tool
    },

    /** 点击格子：放置/移除障碍物 */
    toggleObstacle(x, y) {
      const idx = this.editObstacles.findIndex(p => p.x === x && p.y === y)
      if (idx >= 0) {
        this.editObstacles.splice(idx, 1)
      } else {
        // 检查是否在小车保护区
        const key = x + ',' + y
        if (!this.editProtectedCells.has(key)) {
          this.editObstacles.push({ x, y })
        }
      }
    },

    /** 点击格子：设置小车初始位置 */
    setCarPosition(carId, x, y) {
      // 先检查该位置是否已被其他小车占据
      const otherCar = this.editCarPositions.find(c => c.carId !== carId && c.x === x && c.y === y)
      if (otherCar) return // 不能放到其他小车上
      // 检查是否是障碍物
      if (this.editObstacles.some(p => p.x === x && p.y === y)) return

      const idx = this.editCarPositions.findIndex(c => c.carId === carId)
      if (idx >= 0) {
        // 用 splice 替换整个对象以触发 Pinia 响应式更新
        const car = this.editCarPositions[idx]
        this.editCarPositions.splice(idx, 1, { carId: car.carId, x, y })
      }
    },

    /** 编辑模式下增加小车 */
    addEditCar() {
      const idx = this.editCarPositions.length
      const carId = `Car${String(idx + 1).padStart(3, '0')}`
      this.editCarPositions.push({ carId, x: 0, y: 0 })
      this.editCarCount = this.editCarPositions.length
    },

    /** 编辑模式下移除最后一辆小车 */
    removeEditCar() {
      if (this.editCarPositions.length <= 1) return
      this.editCarPositions.pop()
      this.editCarCount = this.editCarPositions.length
    },

    /** 编辑模式下设置小车数量 */
    setEditCarCount(count) {
      const n = Math.max(1, Math.min(count, 50))
      while (this.editCarPositions.length < n) {
        const idx = this.editCarPositions.length
        this.editCarPositions.push({
          carId: `Car${String(idx + 1).padStart(3, '0')}`,
          x: 0,
          y: 0
        })
      }
      while (this.editCarPositions.length > n) {
        this.editCarPositions.pop()
      }
      this.editCarCount = n
    },

    /** 获取编辑模式的配置数据（用于发送到后端） */
    getEditConfig() {
      return {
        mapWidth: this.editMapWidth,
        mapHeight: this.editMapHeight,
        carCount: this.editCarPositions.length,
        obstacleDensity: 0,
        algorithm: this.config.algorithm || 'A_STAR',
        customObstacles: [...this.editObstacles],
        carPositions: this.editCarPositions.map(c => ({ carId: c.carId, x: c.x, y: c.y }))
      }
    }
  }
})
