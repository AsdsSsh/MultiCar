<script setup>
import { STATUS_LABELS, STATUS_COLORS } from '../utils/constants.js'

defineProps({
  car: { type: Object, required: true },
  color: { type: String, default: '#4fc3f7' },
  index: { type: Number, default: 0 },
  active: { type: Boolean, default: false },
  readonly: { type: Boolean, default: false }
})
const emit = defineEmits(['hover', 'leave', 'delete-car', 'move-car'])

function label(s) {
  return STATUS_LABELS[s] || s || '未知'
}
function statusColor(s) {
  return STATUS_COLORS[s] || '#9e9e9e'
}
</script>

<template>
  <div
    class="car-item"
    :class="{ active }"
    @mouseenter="emit('hover', car.carId)"
    @mouseleave="emit('leave')"
  >
    <div class="line1">
      <span class="dot" :style="{ background: color }"></span>
      <span class="cid">{{ car.carId }}</span>
      <span class="status" :style="{ color: statusColor(car.status) }">{{ label(car.status) }}</span>
      <span class="steps">步数:{{ car.steps ?? 0 }}</span>
      <button v-if="!readonly" class="act-btn move-btn" title="移动小车到新位置" @click.stop="emit('move-car', car.carId)">📍</button>
      <button v-if="!readonly" class="act-btn del-btn" title="删除小车" @click.stop="emit('delete-car', car.carId)">✕</button>
    </div>
    <div class="line2">
      位置({{ car.x }},{{ car.y }}) → 目标({{ car.targetX ?? '-' }},{{ car.targetY ?? '-' }})
    </div>
  </div>
</template>

<style scoped>
.car-item {
  background: #262626;
  border: 1px solid transparent;
  border-radius: 7px;
  padding: 9px 11px;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}
.car-item:hover,
.car-item.active {
  border-color: #4fc3f7;
  background: #2c2c2c;
}
.line1 {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
}
.dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  flex-shrink: 0;
}
.cid {
  font-weight: 600;
  color: #eee;
}
.status {
  font-weight: 600;
}
.steps {
  margin-left: auto;
  color: #999;
  font-size: 12px;
}
.line2 {
  margin-top: 4px;
  font-size: 12px;
  color: #888;
  padding-left: 16px;
}

/* 操作按钮 */
.act-btn {
  width: 22px;
  height: 22px;
  border: 1px solid #444;
  border-radius: 4px;
  background: #2a2a2a;
  color: #999;
  font-size: 11px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  flex-shrink: 0;
  transition: all 0.15s;
  line-height: 1;
}
.move-btn:hover {
  background: rgba(79, 195, 247, 0.2);
  border-color: #4fc3f7;
  color: #4fc3f7;
}
.del-btn:hover {
  background: rgba(229, 115, 115, 0.2);
  border-color: #e57373;
  color: #e57373;
}
</style>
