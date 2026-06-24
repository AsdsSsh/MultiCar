<script setup>
import { ref } from 'vue'
import { useSimulationStore } from '../store/simulationStore.js'
import { CAR_COLORS } from '../utils/constants.js'

const emit = defineEmits(['confirm', 'cancel'])
const store = useSimulationStore()

function getCarColor(idx) {
  return CAR_COLORS[idx % CAR_COLORS.length]
}

function selectTool(tool) {
  store.setEditTool(tool)
  if (tool === 'car') {
    // 自动选中第一辆小车
    if (store.editCarPositions.length > 0) {
      store.setActiveCar(store.editCarPositions[0].carId)
    }
  }
}

function selectCar(carId) {
  store.setActiveCar(carId)
  store.setEditTool('car')
}

function onCarCountChange(e) {
  store.setEditCarCount(Number(e.target.value))
}

function onConfirm() {
  emit('confirm', store.getEditConfig())
}

function onCancel() {
  emit('cancel')
}
</script>

<template>
  <div class="edit-toolbar">
    <h3>地图编辑器</h3>

    <!-- 工具选择 -->
    <div class="tool-section">
      <span class="label">编辑工具</span>
      <div class="tool-btns">
        <button
          :class="['tool-btn', { active: store.editTool === 'obstacle' }]"
          @click="selectTool('obstacle')"
        >
          🧱 障碍物
        </button>
        <button
          :class="['tool-btn', { active: store.editTool === 'car' }]"
          @click="selectTool('car')"
        >
          🚗 小车位置
        </button>
      </div>
    </div>

    <!-- 小车数量 -->
    <div class="tool-section">
      <span class="label">小车数量</span>
      <input
        type="number"
        :value="store.editCarCount"
        min="1"
        max="50"
        @change="onCarCountChange"
        class="car-count-input"
      />
    </div>

    <!-- 小车列表 -->
    <div class="tool-section">
      <span class="label">选择小车（左键点小车选中，左键点空地放置）</span>
      <div class="car-list">
        <div
          v-for="(car, idx) in store.editCarPositions"
          :key="car.carId"
          :class="['car-item', { active: store.activeCarId === car.carId }]"
          @click="selectCar(car.carId)"
        >
          <span class="car-dot" :style="{ background: getCarColor(idx) }"></span>
          <span class="car-id">{{ car.carId }}</span>
          <span class="car-pos">({{ car.x }}, {{ car.y }})</span>
        </div>
      </div>
    </div>

    <!-- 障碍物统计 -->
    <div class="tool-section">
      <span class="label">障碍物：{{ store.editObstacles.length }} 个</span>
    </div>

    <!-- 操作按钮 -->
    <div class="tool-actions">
      <button class="btn primary" @click="onConfirm">✅ 确认并发送</button>
      <button class="btn" @click="onCancel">取消</button>
    </div>
  </div>
</template>

<style scoped>
.edit-toolbar {
  width: 260px;
  background: #1e1e1e;
  border-left: 1px solid #333;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  overflow-y: auto;
}
h3 {
  font-size: 15px;
  color: #ff9800;
  margin-bottom: 4px;
}
.tool-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.label {
  font-size: 12px;
  color: #888;
}
.tool-btns {
  display: flex;
  gap: 8px;
}
.tool-btn {
  flex: 1;
  padding: 8px 6px;
  background: #2a2a2a;
  border: 1px solid #444;
  border-radius: 6px;
  color: #ccc;
  font-size: 12px;
}
.tool-btn:hover {
  background: #333;
}
.tool-btn.active {
  border-color: #ff9800;
  background: rgba(255, 152, 0, 0.12);
  color: #ff9800;
}
.car-count-input {
  width: 80px;
  background: #1d1d1d;
  border: 1px solid #444;
  border-radius: 5px;
  color: #eee;
  padding: 6px 8px;
  font-size: 13px;
}
.car-count-input:focus {
  outline: none;
  border-color: #4fc3f7;
}
.car-list {
  max-height: 300px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.car-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 6px;
  background: #2a2a2a;
  border: 1px solid transparent;
  cursor: pointer;
  font-size: 12px;
}
.car-item:hover {
  background: #333;
}
.car-item.active {
  border-color: #4fc3f7;
  background: rgba(79, 195, 247, 0.1);
}
.car-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}
.car-id {
  color: #ddd;
  font-weight: 600;
}
.car-pos {
  color: #888;
  font-size: 11px;
  margin-left: auto;
}
.tool-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: auto;
}
.btn {
  background: #3a3a3a;
  color: #eee;
  border: 1px solid #4a4a4a;
  border-radius: 6px;
  padding: 8px 16px;
  font-size: 13px;
  width: 100%;
}
.btn:hover {
  background: #4a4a4a;
}
.btn.primary {
  background: #4fc3f7;
  border-color: #4fc3f7;
  color: #10242e;
  font-weight: 600;
}
.btn.primary:hover {
  background: #6fcef9;
}
</style>
