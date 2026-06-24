// ============================================================
// Canvas 绘制工具函数（纯函数，无副作用依赖外部状态）
// 对应方案设计文档「五、Canvas渲染设计」渲染分层 1~8
// ============================================================
import { COLORS, CAR_COLORS, ROUTE_ALPHA } from './constants.js'

/** 计算方格尺寸与居中偏移（文档 5.2） */
export function computeGeometry(canvasW, canvasH, mapW, mapH) {
  // 严格校验所有参数为有效正数
  const w = Number(mapW)
  const h = Number(mapH)
  const cw = Number(canvasW)
  const ch = Number(canvasH)
  if (!(w > 0 && h > 0 && cw > 0 && ch > 0)) {
    return { cellSize: 0, offsetX: 0, offsetY: 0, mapW: w, mapH: h }
  }
  const cellSize = Math.floor(Math.min(cw / w, ch / h))
  // cellSize 至少为 1px 才能渲染
  if (cellSize < 1) {
    return { cellSize: 0, offsetX: 0, offsetY: 0, mapW: w, mapH: h }
  }
  const offsetX = Math.floor((cw - cellSize * w) / 2)
  const offsetY = Math.floor((ch - cellSize * h) / 2)
  return { cellSize, offsetX, offsetY, mapW: w, mapH: h }
}

/** 从小车ID中提取编号（如 Car003 → 3） */
export function extractCarNumber(carId) {
  if (!carId) return 0
  const m = carId.match(/\d+/)
  return m ? parseInt(m[0], 10) : 0
}

/** 取第 index 辆小车的颜色（循环复用） */
export function getCarColor(index) {
  return CAR_COLORS[index % CAR_COLORS.length]
}

function cellPixel(x, y, geo) {
  return { px: geo.offsetX + x * geo.cellSize, py: geo.offsetY + y * geo.cellSize }
}

function cellCenter(x, y, geo) {
  const half = geo.cellSize / 2
  return { cx: geo.offsetX + x * geo.cellSize + half, cy: geo.offsetY + y * geo.cellSize + half }
}

/** 层2：探索层（已探索/未探索底色） */
export function drawExploration(ctx, mapView, geo) {
  const { cellSize } = geo
  for (let y = 0; y < mapView.length; y++) {
    const row = mapView[y]
    if (!row) continue
    for (let x = 0; x < row.length; x++) {
      // 已探索：浅色；未探索：统一灰色
      ctx.fillStyle = row[x] ? COLORS.EXPLORED : COLORS.UNEXPLORED
      const { px, py } = cellPixel(x, y, geo)
      ctx.fillRect(px, py, cellSize, cellSize)
    }
  }
}

/** 层1：网格线（绘制于探索底色之上以保证可见） */
export function drawGrid(ctx, mapW, mapH, geo) {
  const { cellSize, offsetX, offsetY } = geo
  if (!cellSize) return
  ctx.strokeStyle = COLORS.GRID
  ctx.lineWidth = 1
  ctx.beginPath()
  for (let x = 0; x <= mapW; x++) {
    const px = offsetX + x * cellSize + 0.5
    ctx.moveTo(px, offsetY)
    ctx.lineTo(px, offsetY + mapH * cellSize)
  }
  for (let y = 0; y <= mapH; y++) {
    const py = offsetY + y * cellSize + 0.5
    ctx.moveTo(offsetX, py)
    ctx.lineTo(offsetX + mapW * cellSize, py)
  }
  ctx.stroke()
}

/** 层3：障碍层（仅绘制已探索格子上的障碍）
 *  通过 mapView 检查：未探索区域不显示障碍物，避免用户提前看到红色障碍标识 */
export function drawObstacles(ctx, mapBlock, carCellSet, geo, mapView) {
  const { cellSize } = geo
  for (let y = 0; y < mapBlock.length; y++) {
    const row = mapBlock[y]
    if (!row) continue
    for (let x = 0; x < row.length; x++) {
      if (!row[x]) continue
      // 只有已探索的格子才绘制障碍物（未探索区域统一显示为灰色）
      if (!mapView[y]?.[x]) continue
      const isDynamic = carCellSet.has(x + ',' + y)
      const { px, py } = cellPixel(x, y, geo)

      // 底色填充
      ctx.fillStyle = isDynamic ? COLORS.DYNAMIC_OBSTACLE : COLORS.OBSTACLE
      ctx.fillRect(px + 1, py + 1, cellSize - 1, cellSize - 1)

      // X 纹理（格子足够大时才绘制，提升障碍物辨识度）
      if (cellSize >= 6) {
        const lineW = Math.max(1, Math.floor(cellSize * 0.08))
        const pad = Math.max(2, Math.floor(cellSize * 0.12))
        ctx.save()
        ctx.strokeStyle = isDynamic ? 'rgba(255,200,200,0.55)' : 'rgba(0,0,0,0.3)'
        ctx.lineWidth = lineW
        // 对角线1: 左上→右下
        ctx.beginPath()
        ctx.moveTo(px + pad, py + pad)
        ctx.lineTo(px + cellSize - pad, py + cellSize - pad)
        ctx.stroke()
        // 对角线2: 右上→左下
        ctx.beginPath()
        ctx.moveTo(px + cellSize - pad, py + pad)
        ctx.lineTo(px + pad, py + cellSize - pad)
        ctx.stroke()
        ctx.restore()
      }
    }
  }
}

/** 层4：路线层（半透明彩色折线） */
export function drawRoutes(ctx, cars, geo) {
  if (geo.cellSize < 2) return
  ctx.save()
  ctx.globalAlpha = ROUTE_ALPHA
  ctx.lineWidth = 2
  ctx.lineJoin = 'round'
  ctx.lineCap = 'round'
  cars.forEach((car) => {
    const route = car.route
    if (!Array.isArray(route) || route.length < 2) return
    const num = extractCarNumber(car.carId)
    ctx.strokeStyle = getCarColor(num - 1)
    ctx.beginPath()
    for (let k = 0; k < route.length; k++) {
      const pt = route[k]
      if (!pt) continue
      const { cx, cy } = cellCenter(pt[0], pt[1], geo)
      if (k === 0) ctx.moveTo(cx, cy)
      else ctx.lineTo(cx, cy)
    }
    ctx.stroke()
  })
  ctx.restore()
}

/** 层5：目标层（金黄色星形标记） */
export function drawTargets(ctx, cars, geo) {
  const r = Math.max(4, geo.cellSize * 0.35)
  cars.forEach((car) => {
    if (typeof car.targetX !== 'number' || typeof car.targetY !== 'number') return
    if (car.targetX < 0 || car.targetY < 0) return
    const { cx, cy } = cellCenter(car.targetX, car.targetY, geo)
    drawStar(ctx, cx, cy, 5, r, r * 0.45)
    ctx.fillStyle = COLORS.TARGET
    ctx.fill()
  })
}

function drawStar(ctx, cx, cy, spikes, outerR, innerR) {
  let rot = -Math.PI / 2
  const step = Math.PI / spikes
  ctx.beginPath()
  ctx.moveTo(cx + Math.cos(rot) * outerR, cy + Math.sin(rot) * outerR)
  for (let i = 0; i < spikes; i++) {
    rot += step
    ctx.lineTo(cx + Math.cos(rot) * innerR, cy + Math.sin(rot) * innerR)
    rot += step
    ctx.lineTo(cx + Math.cos(rot) * outerR, cy + Math.sin(rot) * outerR)
  }
  ctx.closePath()
}

/** 层6：小车层（圆形车身 + 方向指针 + 高亮环 + 序号） */
export function drawCars(ctx, cars, geo, activeCarId) {
  const r = Math.max(3, geo.cellSize * 0.38)
  cars.forEach((car) => {
    if (typeof car.x !== 'number' || typeof car.y !== 'number') return
    const { cx, cy } = cellCenter(car.x, car.y, geo)
    const num = extractCarNumber(car.carId)
    const color = getCarColor(num - 1)

    // 联动高亮环
    if (activeCarId && car.carId === activeCarId) {
      ctx.beginPath()
      ctx.arc(cx, cy, r + 3, 0, Math.PI * 2)
      ctx.strokeStyle = '#ffffff'
      ctx.lineWidth = 2
      ctx.stroke()
    }

    // 方向指针（先画，被车身覆盖根部）
    const dir = carDirection(car)
    if (dir) {
      const ang = Math.atan2(dir.dy, dir.dx)
      const tip = r + Math.max(3, geo.cellSize * 0.2)
      const wing = r * 0.75
      ctx.beginPath()
      ctx.moveTo(cx + Math.cos(ang) * tip, cy + Math.sin(ang) * tip)
      ctx.lineTo(cx + Math.cos(ang + 2.4) * wing, cy + Math.sin(ang + 2.4) * wing)
      ctx.lineTo(cx + Math.cos(ang - 2.4) * wing, cy + Math.sin(ang - 2.4) * wing)
      ctx.closePath()
      ctx.fillStyle = color
      ctx.fill()
    }

    // 车身
    ctx.beginPath()
    ctx.arc(cx, cy, r, 0, Math.PI * 2)
    ctx.fillStyle = color
    ctx.fill()
    ctx.strokeStyle = '#000000'
    ctx.lineWidth = 1
    ctx.stroke()

    // 序号（格子足够大时，从carId中提取编号确保与右侧列表一致）
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

/** 推断小车朝向：优先路径下一步，其次目标方向 */
function carDirection(car) {
  const route = car.route
  if (Array.isArray(route) && route.length >= 2) {
    let idx = route.findIndex((p) => p && p[0] === car.x && p[1] === car.y)
    if (idx === -1) idx = 0
    const next = route[idx + 1] || route[idx]
    if (next) {
      const dx = next[0] - car.x
      const dy = next[1] - car.y
      const n = Math.hypot(dx, dy)
      if (n > 0) return { dx: dx / n, dy: dy / n }
    }
  }
  if (typeof car.targetX === 'number' && typeof car.targetY === 'number') {
    const dx = car.targetX - car.x
    const dy = car.targetY - car.y
    const n = Math.hypot(dx, dy)
    if (n > 0) return { dx: dx / n, dy: dy / n }
  }
  return null
}

/** 层7：悬停层（半透明高亮 + 边框） */
export function drawHover(ctx, cell, geo) {
  if (!cell) return
  const { px, py } = cellPixel(cell.x, cell.y, geo)
  ctx.fillStyle = COLORS.HOVER
  ctx.fillRect(px, py, geo.cellSize, geo.cellSize)
  ctx.strokeStyle = '#ffffff'
  ctx.lineWidth = 1.5
  ctx.strokeRect(px + 0.5, py + 0.5, geo.cellSize - 1, geo.cellSize - 1)
}

/** 层8：HUD 层（格点坐标 + 状态浮层） */
export function drawHUD(ctx, cell, info, geo) {
  if (!cell) return
  const statusText = info.car
    ? `小车: ${info.car.carId}`
    : info.blocked
      ? '障碍'
      : info.explored
        ? '已探索'
        : '未探索'
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

  ctx.fillStyle = COLORS.HUD_BG
  ctx.fillRect(px, py, w, h)
  ctx.fillStyle = COLORS.HUD_TEXT
  lines.forEach((t, idx) => ctx.fillText(t, px + padding, py + padding + idx * lineH))
}
