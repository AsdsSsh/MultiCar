<script setup>
import { ref, watch } from 'vue'
import { ALGORITHMS, DEFAULT_CONFIG } from '../utils/constants.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  config: { type: Object, default: () => ({ ...DEFAULT_CONFIG }) }
})
const emit = defineEmits(['confirm', 'cancel'])

const form = ref({ ...DEFAULT_CONFIG })

// 每次打开弹窗时，用当前配置回填表单
watch(
  () => props.visible,
  (v) => {
    if (v) form.value = { ...DEFAULT_CONFIG, ...props.config }
  }
)

function clamp(v, min, max) {
  const n = Number(v)
  if (Number.isNaN(n)) return min
  return Math.min(max, Math.max(min, n))
}

function onConfirm() {
  const f = form.value
  const payload = {
    mapWidth: clamp(f.mapWidth, 5, 200),
    mapHeight: clamp(f.mapHeight, 5, 200),
    carCount: clamp(f.carCount, 0, 50),
    obstacleDensity: clamp(f.obstacleDensity, 0, 0.9),
    algorithm: f.algorithm
  }
  emit('confirm', payload)
}
</script>

<template>
  <div v-if="visible" class="overlay" @click.self="$emit('cancel')">
    <div class="dialog">
      <h2>仿真配置</h2>
      <label>
        <span>地图宽度</span>
        <input type="number" v-model.number="form.mapWidth" min="5" max="200" />
      </label>
      <label>
        <span>地图高度</span>
        <input type="number" v-model.number="form.mapHeight" min="5" max="200" />
      </label>
      <label>
        <span>小车数量</span>
        <input type="number" v-model.number="form.carCount" min="0" max="50" />
      </label>
      <label>
        <span>障碍密度</span>
        <input type="number" step="0.05" v-model.number="form.obstacleDensity" min="0" max="0.9" />
      </label>
      <label>
        <span>路径算法</span>
        <select v-model="form.algorithm">
          <option v-for="a in ALGORITHMS" :key="a.value" :value="a.value">{{ a.label }}</option>
        </select>
      </label>
      <div class="dlg-actions">
        <button class="btn" @click="$emit('cancel')">取消</button>
        <button class="btn primary" @click="onConfirm">确认</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}
.dialog {
  width: 320px;
  background: #262626;
  border: 1px solid #383838;
  border-radius: 10px;
  padding: 20px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.5);
}
h2 {
  font-size: 16px;
  margin-bottom: 16px;
  color: #fff;
}
label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  font-size: 13px;
  color: #bbb;
}
input,
select {
  width: 150px;
  background: #1d1d1d;
  border: 1px solid #444;
  border-radius: 5px;
  color: #eee;
  padding: 6px 8px;
  font-size: 13px;
}
input:focus,
select:focus {
  outline: none;
  border-color: #4fc3f7;
}
.dlg-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 20px;
}
.btn {
  background: #3a3a3a;
  color: #eee;
  border: 1px solid #4a4a4a;
  border-radius: 6px;
  padding: 7px 16px;
  font-size: 13px;
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
