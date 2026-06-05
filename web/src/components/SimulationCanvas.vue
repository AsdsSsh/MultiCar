<script setup>
import { ref } from 'vue'
import { useSimulationStore } from '../store/simulationStore.js'
import { useCanvasRenderer } from '../composables/useCanvasRenderer.js'

const emit = defineEmits(['car-hover'])
const store = useSimulationStore()
const canvasRef = ref(null)
const { pixelToCell } = useCanvasRenderer(canvasRef, store)

function onMouseMove(e) {
  const cell = pixelToCell(e.clientX, e.clientY)
  store.setHoveredCell(cell)
  // 悬停到小车格 → 联动高亮 + 通知父级
  if (cell) {
    const car = store.cars.find((c) => c.x === cell.x && c.y === cell.y)
    const carId = car ? car.carId : null
    store.setActiveCar(carId)
    emit('car-hover', carId)
  } else {
    store.setActiveCar(null)
  }
}

function onMouseLeave() {
  store.setHoveredCell(null)
  store.setActiveCar(null)
  emit('car-hover', null)
}
</script>

<template>
  <div class="canvas-host">
    <canvas ref="canvasRef" @mousemove="onMouseMove" @mouseleave="onMouseLeave"></canvas>
    <div class="legend">
      <span><i class="sw" style="background: #cc4444"></i>障碍</span>
      <span><i class="sw" style="background: #2d2d2d"></i>未探索</span>
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
</style>
