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
  drawHUD
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
    const mapW = store.config.mapWidth
    const mapH = store.config.mapHeight
    geo = computeGeometry(cssW, cssH, mapW, mapH)

    ctx.fillStyle = COLORS.BACKGROUND
    ctx.fillRect(0, 0, cssW, cssH)
    if (!geo.cellSize) return

    if (store.mapView.length) drawExploration(ctx, store.mapView, geo)
    drawGrid(ctx, mapW, mapH, geo)
    if (store.mapBlock.length) {
      const carCells = new Set(store.cars.map((c) => c.x + ',' + c.y))
      drawObstacles(ctx, store.mapBlock, carCells, geo)
    }
    drawRoutes(ctx, store.cars, geo)
    drawTargets(ctx, store.cars, geo)
    drawCars(ctx, store.cars, geo, store.activeCarId)

    if (store.hoveredCell) {
      drawHover(ctx, store.hoveredCell, geo)
      drawHUD(ctx, store.hoveredCell, store.cellInfo(store.hoveredCell.x, store.hoveredCell.y), geo)
    }
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
    if (x < 0 || y < 0 || x >= store.config.mapWidth || y >= store.config.mapHeight) return null
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

  return { pixelToCell, markDirty }
}
