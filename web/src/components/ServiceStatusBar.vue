<script setup>
import { computed, onMounted } from 'vue'
import { useSimulationStore } from '../store/simulationStore.js'
import { api } from '../utils/api.js'

const store = useSimulationStore()
const services = computed(() => store.services)

const config = {
  controller:     { label: '控制器',   color: '#4fc3f7' },
  navigator:      { label: '导航器',   color: '#81c784' },
  targetplanner:  { label: '目标规划', color: '#ffd54f' },
  carpool:        { label: '小车池',   color: '#ff9800' },
  taskconfig:     { label: '任务配置', color: '#ce93d8' },
}

onMounted(async () => {
  try {
    const res = await api.getServiceStatus()
    store.setServices(res.services || {})
  } catch (e) { /* */ }
})
</script>

<template>
  <div class="service-bar" v-if="Object.keys(services).length">
    <span
      v-for="(cfg, type) in config"
      :key="type"
      class="item"
      :title="`${cfg.label}: ${services[type]?.length || 0} 在线`"
    >
      <span
        class="dot"
        :style="{ background: (services[type]?.length > 0) ? cfg.color : '#555' }"
      ></span>
      {{ cfg.label }}×{{ services[type]?.length || 0 }}
    </span>
  </div>
</template>

<style scoped>
.service-bar {
  display: flex;
  gap: 10px;
  align-items: center;
}
.item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: #999;
}
.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
}
</style>
