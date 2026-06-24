<script setup>
import { ref } from 'vue'
import { useSimulationStore } from '../store/simulationStore.js'
import SimParams from './SimParams.vue'
import SimControls from './SimControls.vue'
import CarStatusList from './CarStatusList.vue'
import MapManager from './MapManager.vue'

defineEmits(['start', 'pause', 'step', 'reset', 'addCar'])
const store = useSimulationStore()
const activeTab = ref('simulation') // 'simulation' | 'maps'
</script>

<template>
  <aside class="panel">
    <!-- Tab 导航 -->
    <div class="tab-bar">
      <button
        class="tab-btn"
        :class="{ active: activeTab === 'simulation' }"
        @click="activeTab = 'simulation'"
      >仿真控制</button>
      <button
        class="tab-btn"
        :class="{ active: activeTab === 'maps' }"
        @click="activeTab = 'maps'"
      >地图管理</button>
    </div>

    <!-- 仿真控制内容 -->
    <template v-if="activeTab === 'simulation'">
      <SimParams :config="store.config" :tick="store.tick" />
      <SimControls
        :is-running="store.isRunning"
        @start="$emit('start')"
        @pause="$emit('pause')"
        @step="$emit('step')"
        @reset="$emit('reset')"
        @add-car="$emit('addCar')"
      />
      <CarStatusList :cars="store.cars" />
    </template>

    <!-- 地图管理内容 -->
    <MapManager v-else />
  </aside>
</template>

<style scoped>
.panel {
  width: 320px;
  flex-shrink: 0;
  background: #1e1e1e;
  border-left: 1px solid #333;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  padding: 16px;
  gap: 18px;
}

.tab-bar {
  display: flex;
  gap: 4px;
  background: #262626;
  border-radius: 8px;
  padding: 4px;
}

.tab-btn {
  flex: 1;
  border: none;
  background: transparent;
  color: #888;
  font-size: 13px;
  font-weight: 600;
  padding: 8px 0;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s;
}

.tab-btn.active {
  background: #3a3a3a;
  color: #e8e8e8;
}

.tab-btn:hover:not(.active) {
  color: #bbb;
}
</style>
