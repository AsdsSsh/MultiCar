<script setup>
import { ref, watch } from 'vue'
import { useSimulationStore } from '../store/simulationStore.js'
import { useCanvasRenderer } from '../composables/useCanvasRenderer.js'

const emit = defineEmits(['car-hover', 'cell-pick'])
const props = defineProps({
  pickMode: { type: Boolean, default: false }
})
const store = useSimulationStore()
const canvasRef = ref(null)
const { pixelToCell, markDirty } = useCanvasRenderer(canvasRef, store)

// 当 pickMode 变化时触发重绘（显示/隐藏选择指示器）
watch(() => props.pickMode, () => markDirty())

// ===== 地图编辑模式 / 选位置模式：点击交互 =====
function onClick(e) {
  const cell = pixelToCell(e.clientX, e.clientY)
  if (!cell) return

  // 增加小车选位置模式：点击地图选择坐标
  if (props.pickMode) {
    emit('cell-pick', { x: cell.x, y: cell.y })
    return
  }

  if (!store.editMode) return

  const { x, y } = cell

  if (store.editTool === 'obstacle') {
    store.toggleObstacle(x, y)
  } else if (store.editTool === 'car') {
    // 左键点击小车 → 选中；左键点击空地 → 放置已选中的小车
    const clickedCar = store.editCarPositions.find(c => c.x === x && c.y === y)
    if (clickedCar) {
      // 点击到小车 → 选中
      store.setActiveCar(clickedCar.carId)
    } else {
      // 点击空地 → 放置已选中的小车
      const activeCar = store.editCarPositions.find(c => c.carId === store.activeCarId)
      if (activeCar) {
        store.setCarPosition(activeCar.carId, x, y)
      }
    }
  }
}

// ===== 编辑模式下鼠标悬停 → 同步高亮 =====
function onMouseMove(e) {
  const cell = pixelToCell(e.clientX, e.clientY)
  store.setHoveredCell(cell)

  // 增加小车选位置模式：悬停时显示预览框
  if (props.pickMode && cell) {
    store.setHoveredCell(cell)
    return
  }

  if (store.editMode) {
    // 编辑模式下：悬停时高亮编辑小车（但不改变选中状态）
    if (cell) {
      const editCar = store.editCarPositions.find(c => c.x === cell.x && c.y === cell.y)
      if (editCar && store.editTool === 'car') {
        emit('car-hover', editCar.carId)
      } else {
        emit('car-hover', null)
      }
    } else {
      emit('car-hover', null)
    }
  } else {
    // 正常模式：悬停到小车格 → 联动高亮 + 通知父级
    if (cell) {
      const car = store.cars.find((c) => c.x === cell.x && c.y === cell.y)
      const carId = car ? car.carId : null
      store.setActiveCar(carId)
      emit('car-hover', carId)
    } else {
      store.setActiveCar(null)
    }
  }
}
</script>

<template>
  <div class="canvas-host" :class="{ 'edit-mode': store.editMode, 'pick-mode': props.pickMode }">
    <canvas
      ref="canvasRef"
      @mousemove="onMouseMove"
      @mouseleave="onMouseLeave"
      @click="onClick"
    ></canvas>

    <!-- pick 模式指示器 -->
    <div v-if="props.pickMode" class="pick-indicator">
      <span class="pick-badge">📍 点击地图选择位置</span>
    </div>

    <!-- 编辑模式指示器 -->
    <div v-if="store.editMode && !props.pickMode" class="edit-indicator">
      <span class="edit-badge">编辑模式</span>
      <span v-if="store.editTool === 'obstacle'" class="tool-hint">左键点击放置/移除障碍物</span>
      <span v-else class="tool-hint">左键点小车选中，再左键点空地放置</span>
    </div>

    <div class="legend">
      <span><i class="sw obstacle-sw"></i>障碍</span>
      <span><i class="sw" style="background: #5a5a5a"></i>未探索</span>
      <span><i class="sw" style="background: #dcdcdc"></i>已探索</span>
      <span><i class="sw round" style="background: #4fc3f7"></i>小车</span>
      <span><i class="sw" style="background: #ffaa00"></i>目标</span>
    </div>
  </div>
</template>

<style scoped>
.canvas-host {
  position: absolute;
  inset: 0;
}
.canvas-host.edit-mode {
  cursor: crosshair;
}
.canvas-host.pick-mode {
  cursor: pointer;
}
canvas {
  display: block;
  width: 100%;
  height: 100%;
  cursor: crosshair;
}
.legend {
  position: absolute;
  left: 12px;
  bottom: 12px;
  display: flex;
  gap: 14px;
  padding: 6px 12px;
  background: rgba(0, 0, 0, 0.55);
  border-radius: 6px;
  font-size: 12px;
  color: #ddd;
  pointer-events: none;
}
.legend span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.sw {
  width: 12px;
  height: 12px;
  display: inline-block;
  border: 1px solid rgba(255, 255, 255, 0.2);
}
.sw.round {
  border-radius: 50%;
}
.obstacle-sw {
  background: #cc4444;
  position: relative;
}
.obstacle-sw::after {
  content: '';
  position: absolute;
  inset: 0;
  background: 
    linear-gradient(to top right, transparent calc(50% - 1px), rgba(0,0,0,0.35) calc(50% - 1px), rgba(0,0,0,0.35) calc(50% + 1px), transparent calc(50% + 1px)),
    linear-gradient(to bottom right, transparent calc(50% - 1px), rgba(0,0,0,0.35) calc(50% - 1px), rgba(0,0,0,0.35) calc(50% + 1px), transparent calc(50% + 1px));
}

/* pick 模式指示器 */
.pick-indicator {
  position: absolute;
  top: 12px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 16px;
  background: rgba(79, 195, 247, 0.15);
  border: 1px solid #4fc3f7;
  border-radius: 20px;
  font-size: 14px;
  color: #b3e5fc;
  pointer-events: none;
  z-index: 10;
}
.pick-badge {
  font-weight: 700;
  color: #4fc3f7;
}

/* 编辑模式指示器 */
.edit-indicator {
  position: absolute;
  top: 12px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 16px;
  background: rgba(255, 152, 0, 0.15);
  border: 1px solid #ff9800;
  border-radius: 20px;
  font-size: 13px;
  color: #ffcc80;
  pointer-events: none;
  z-index: 10;
}
.edit-badge {
  font-weight: 700;
  color: #ff9800;
}
.tool-hint {
  font-size: 12px;
  color: #bbb;
}
</style>
