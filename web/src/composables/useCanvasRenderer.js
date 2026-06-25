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

const ANIM_DURATION_MS = 500 // 匹配 tick 间隔，消除停顿

export function useCanvasRenderer(canvasRef, store) {
  let ctx = null
  let rafId = null
  let dirty = true
  let geo = { cellSize: 0, offsetX: 0, offsetY: 0 }

  // 动画状态
  let prevCars = new Map()
  let animStart = 0
  let animating = false

  function markDirty() {
    dirty = true
  }

  function startCarAnimation() {
    for (const c of store.cars) {
      prevCars.set(c.carId, { x: c.x, y: c.y })
    }
    animStart = performance.now()
    animating = true
    dirty = true
  }

  function getInterpolatedCars() {
    const elapsed = animating ? performance.now() - animStart : ANIM_DURATION_MS
    const t = Math.min(elapsed / ANIM_DURATION_MS, 1.0)
    const eased = 1 - (1 - t) * (1 - t)

    return store.cars.map(c => {
      const prev = prevCars.get(c.carId)
      if (!prev || eased >= 1) return c
      return {
        ...c,
        x: prev.x + (c.x - prev.x) * eased,
        y: prev.y + (c.y - prev.y) * eased,
      }
    })
  }

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

  function draw() {
    const canvas = canvasRef.value
    if (!canvas || !ctx) return
    const cssW = canvas.clientWidth
    const cssH = canvas.clientHeight
    const mapW = store.editMode ? store.editMapWidth : store.config.mapWidth
    const mapH = store.editMode ? store.editMapHeight : store.config.mapHeight
    geo = computeGeometry(cssW, cssH, mapW, mapH)

    ctx.fillStyle = COLORS.BACKGROUND
    ctx.fillRect(0, 0, cssW, cssH)
    if (!geo.cellSize) return

    if (store.editMode) {
      drawGrid(ctx, mapW, mapH, geo)
      drawEditProtectedZones(ctx, geo)
      drawEditObstacles(ctx, geo)
      drawEditCars(ctx, geo)
    } else {
      if (store.mapView.length) drawExploration(ctx, store.mapView, geo)
      drawGrid(ctx, mapW, mapH, geo)
      if (store.mapBlock.length) {
        const carCells = new Set(store.cars.map((c) => c.x + ',' + c.y))
        drawObstacles(ctx, store.mapBlock, carCells, geo, store.mapView)
      }
      drawRoutes(ctx, store.cars, geo)
      drawTargets(ctx, store.cars, geo)
      const cars = getInterpolatedCars()
      drawCars(ctx, cars, geo, store.activeCarId)
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

  function drawEditObstacles(ctx, geo) {
    const { cellSize } = geo
    for (const p of store.editObstacles) {
      const px = geo.offsetX + p.x * cellSize
      const py = geo.offsetY + p.y * cellSize
      ctx.fillStyle = '#cc4444'
      ctx.fillRect(px + 1, py + 1, cellSize - 1, cellSize - 1)
    }
  }

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

  function drawEditCars(ctx, geo) {
    const r = Math.max(3, geo.cellSize * 0.38)
    store.editCarPositions.forEach((car, i) => {
      const cx = geo.offsetX + car.x * geo.cellSize + geo.cellSize / 2
      const cy = geo.offsetY + car.y * geo.cellSize + geo.cellSize / 2
      const color = ['#4fc3f7', '#ff8a65', '#81c784', '#ffd54f', '#ba68c8'][i % 5]
      if (store.activeCarId === car.carId) {
        ctx.beginPath()
        ctx.arc(cx, cy, r + 3, 0, Math.PI * 2)
        ctx.strokeStyle = '#ffffff'
        ctx.lineWidth = 2
        ctx.stroke()
      }
      ctx.beginPath()
      ctx.arc(cx, cy, r, 0, Math.PI * 2)
      ctx.fillStyle = color
      ctx.fill()
      ctx.strokeStyle = '#000'
      ctx.lineWidth = 1
      ctx.stroke()
      if (geo.cellSize >= 14) {
        ctx.fillStyle = '#1a1a1a'
        ctx.font = `bold ${Math.floor(r)}px sans-serif`
        ctx.textAlign = 'center'
        ctx.textBaseline = 'middle'
        ctx.fillText(String(extractCarNumber(car.carId)), cx, cy)
      }
    })
  }

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
    if (animating && performance.now() - animStart < ANIM_DURATION_MS) {
      draw()
    } else {
      if (animating) { animating = false; prevCars.clear() }
      if (!dirty) return
      dirty = false
      draw()
    }
  }

  function pixelToCell(clientX, clientY) {
    const canvas = canvasRef.value
    if (!canvas || !geo.cellSize) return null
    const rect = canvas.getBoundingClientRect()
    const x = Math.floor((clientX - rect.left - geo.offsetX) / geo.cellSize)
    const y = Math.floor((clientY - rect.top - geo.offsetY) / geo.cellSize)
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

  watch(() => store.tick, markDirty)
  watch(() => store.mapView, markDirty)
  watch(() => store.mapBlock, markDirty)
  watch(() => store.hoveredCell, markDirty)
  watch(() => store.activeCarId, markDirty)
  watch(() => [store.config.mapWidth, store.config.mapHeight], markDirty)
  watch(() => store.editMode, markDirty)
  watch(() => store.editTool, markDirty)
  watch(() => store.editObstacles, markDirty, { deep: true })
  watch(() => store.editCarPositions, markDirty, { deep: true })

  watch(() => store.cars, startCarAnimation, { deep: true })

  return { pixelToCell, markDirty }
}
