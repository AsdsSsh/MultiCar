import { onMounted, onBeforeUnmount, watch } from 'vue'
import {
  computeGeometry,
  drawExploration,
  drawGrid,
  drawObstacles,
  drawRoutes,
  drawTargets,
  drawCars,
  drawHover,
  drawHUD,
  extractCarNumber
} from '../utils/canvasDrawer.js'
import { COLORS } from '../utils/constants.js'

// ============================================================
// Canvas 渲染循环（requestAnimationFrame 驱动 + 脏标记重绘）
// 对应方案设计文档「五、Canvas渲染设计 5.3 渲染策略」
// ============================================================
export function useCanvasRenderer(canvasRef, store) {
  let ctx = null
  let rafId = null
  let dirty = true
  let geo = { cellSize: 0, offsetX: 0, offsetY: 0 }

  function markDirty() {
    dirty = true
  }

  /** 根据父容器尺寸 + DPR 调整画布物理像素 */
  function resize() {
    const canvas = canvasRef.value
    if (!canvas || !ctx) return
    const parent = canvas.parentElement
    const w = parent ? parent.clientWidth : canvas.clientWidth
    const h = parent ? parent.clientHeight : canvas.clientHeight
    const dpr = window.devicePixelRatio || 1
    canvas.width = Math.round(w * dpr)
    canvas.height = Math.round(h * dpr)
    canvas.style.width = w + 'px'
    canvas.style.height = h + 'px'
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    dirty = true
  }

  /** 全量重绘（按图层顺序 1~8） */
  function draw() {
    const canvas = canvasRef.value
    if (!canvas || !ctx) return
    const cssW = canvas.clientWidth
    const cssH = canvas.clientHeight
    // 编辑模式下用编辑地图尺寸
    const mapW = store.editMode ? store.editMapWidth : store.config.mapWidth
    const mapH = store.editMode ? store.editMapHeight : store.config.mapHeight
    geo = computeGeometry(cssW, cssH, mapW, mapH)

    ctx.fillStyle = COLORS.BACKGROUND
    ctx.fillRect(0, 0, cssW, cssH)
    if (!geo.cellSize) return

    if (store.editMode) {
      // ===== 编辑模式渲染 =====
      // 画网格
      drawGrid(ctx, mapW, mapH, geo)

      // 画小车保护区（淡色标记）
      drawEditProtectedZones(ctx, geo)

      // 画编辑障碍物
      drawEditObstacles(ctx, geo)

      // 画编辑小车位置
      drawEditCars(ctx, geo)
    } else {
      // ===== 正常仿真渲染 =====
      if (store.mapView.length) drawExploration(ctx, store.mapView, geo)
      drawGrid(ctx, mapW, mapH, geo)
      if (store.mapBlock.length) {
        const carCells = new Set(store.cars.map((c) => c.x + ',' + c.y))
        drawObstacles(ctx, store.mapBlock, carCells, geo, store.mapView)
      }
      drawRoutes(ctx, store.cars, geo)
      drawTargets(ctx, store.cars, geo)
      drawCars(ctx, store.cars, geo, store.activeCarId)
    }

    if (store.hoveredCell) {
      drawHover(ctx, store.hoveredCell, geo)
      if (store.editMode) {
        drawEditHUD(ctx, store.hoveredCell, geo)
      } else {
        drawHUD(ctx, store.hoveredCell, store.cellInfo(store.hoveredCell.x, store.hoveredCell.y), geo)
      }
    }
  }

  // ===== 编辑模式绘制函数 =====

  /** 绘制编辑障碍物 */
  function drawEditObstacles(ctx, geo) {
    const { cellSize } = geo
    for (const p of store.editObstacles) {
      const px = geo.offsetX + p.x * cellSize
      const py = geo.offsetY + p.y * cellSize
      ctx.fillStyle = '#cc4444'
      ctx.fillRect(px + 1, py + 1, cellSize - 1, cellSize - 1)
    }
  }

  /** 绘制小车保护区（小车周围3x3区域用淡色标记） */
  function drawEditProtectedZones(ctx, geo) {
    const { cellSize } = geo
    const cells = store.editProtectedCells
    for (const key of cells) {
      const [x, y] = key.split(',').map(Number)
      const px = geo.offsetX + x * cellSize
      const py = geo.offsetY + y * cellSize
      ctx.fillStyle = 'rgba(79, 195, 247, 0.12)'
      ctx.fillRect(px, py, cellSize, cellSize)
    }
  }

  /** 绘制编辑模式的小车位置 */
  function drawEditCars(ctx, geo) {
    const r = Math.max(3, geo.cellSize * 0.38)
    store.editCarPositions.forEach((car, i) => {
      const cx = geo.offsetX + car.x * geo.cellSize + geo.cellSize / 2
      const cy = geo.offsetY + car.y * geo.cellSize + geo.cellSize / 2
      const color = ['#4fc3f7', '#ff8a65', '#81c784', '#ffd54f', '#ba68c8'][i % 5]

      // 联动高亮环（选中状态）
      if (store.activeCarId === car.carId) {
        ctx.beginPath()
        ctx.arc(cx, cy, r + 3, 0, Math.PI * 2)
        ctx.strokeStyle = '#ffffff'
        ctx.lineWidth = 2
        ctx.stroke()
      }

      // 车身
      ctx.beginPath()
      ctx.arc(cx, cy, r, 0, Math.PI * 2)
      ctx.fillStyle = color
      ctx.fill()
      ctx.strokeStyle = '#000'
      ctx.lineWidth = 1
      ctx.stroke()

      // 序号（从carId中提取编号确保与右侧列表一致）
      if (geo.cellSize >= 14) {
        ctx.fillStyle = '#1a1a1a'
        ctx.font = `bold ${Math.floor(r)}px sans-serif`
        ctx.textAlign = 'center'
        ctx.textBaseline = 'middle'
        const num = extractCarNumber(car.carId)
        ctx.fillText(String(num), cx, cy)
      }
    })
  }

  /** 编辑模式下的 HUD 提示 */
  function drawEditHUD(ctx, cell, geo) {
    const info = store.editCellInfo(cell.x, cell.y)
    let statusText = '空地'
    if (info.isObstacle) statusText = '障碍'
    if (info.car) statusText = `小车: ${info.car.carId}`
    const isProtected = store.editProtectedCells.has(cell.x + ',' + cell.y)
    if (isProtected && !info.car) statusText += ' (保护区)'

    const lines = [`(${cell.x}, ${cell.y})`, statusText]

    ctx.font = '12px sans-serif'
    ctx.textAlign = 'left'
    ctx.textBaseline = 'top'
    const padding = 6
    const lineH = 16
    const w = Math.max(...lines.map((t) => ctx.measureText(t).width)) + padding * 2
    const h = lines.length * lineH + padding * 2

    let { px, py } = cellPixel(cell.x, cell.y, geo)
    px += geo.cellSize + 8
    const maxX = ctx.canvas.clientWidth || ctx.canvas.width
    const maxY = ctx.canvas.clientHeight || ctx.canvas.height
    if (px + w > maxX) px = maxX - w - 4
    if (py + h > maxY) py = maxY - h - 4

    ctx.fillStyle = 'rgba(0, 0, 0, 0.75)'
    ctx.fillRect(px, py, w, h)
    ctx.fillStyle = '#ffffff'
    lines.forEach((t, idx) => ctx.fillText(t, px + padding, py + padding + idx * lineH))
  }

  function cellPixel(x, y, geo) {
    return { px: geo.offsetX + x * geo.cellSize, py: geo.offsetY + y * geo.cellSize }
  }

  function loop() {
    rafId = requestAnimationFrame(loop)
    if (!dirty) return
    dirty = false
    draw()
  }

  /** 将鼠标客户端坐标换算为格点坐标，越界返回 null */
  function pixelToCell(clientX, clientY) {
    const canvas = canvasRef.value
    if (!canvas || !geo.cellSize) return null
    const rect = canvas.getBoundingClientRect()
    const x = Math.floor((clientX - rect.left - geo.offsetX) / geo.cellSize)
    const y = Math.floor((clientY - rect.top - geo.offsetY) / geo.cellSize)
    // 编辑模式下用编辑地图尺寸做边界检查
    const mapW = store.editMode ? store.editMapWidth : store.config.mapWidth
    const mapH = store.editMode ? store.editMapHeight : store.config.mapHeight
    if (x < 0 || y < 0 || x >= mapW || y >= mapH) return null
    return { x, y }
  }

  onMounted(() => {
    ctx = canvasRef.value.getContext('2d')
    resize()
    window.addEventListener('resize', resize)
    loop()
  })

  onBeforeUnmount(() => {
    cancelAnimationFrame(rafId)
    window.removeEventListener('resize', resize)
  })

  // 数据 / 节拍 / 悬停 / 联动 变化时标脏，下一帧重绘
  watch(() => store.tick, markDirty)
  watch(() => store.mapView, markDirty)
  watch(() => store.mapBlock, markDirty)
  watch(() => store.hoveredCell, markDirty)
  watch(() => store.activeCarId, markDirty)
  watch(() => store.cars, markDirty, { deep: true })
  watch(() => [store.config.mapWidth, store.config.mapHeight], markDirty)
  // 编辑模式变化
  watch(() => store.editMode, markDirty)
  watch(() => store.editTool, markDirty)
  watch(() => store.editObstacles, markDirty, { deep: true })
  watch(() => store.editCarPositions, markDirty, { deep: true })

  return { pixelToCell, markDirty }
}
