<script setup>
import { useSimulationStore } from '../store/simulationStore.js'
import SimParams from './SimParams.vue'
import SimControls from './SimControls.vue'
import CarStatusList from './CarStatusList.vue'

defineEmits(['start', 'pause', 'step', 'reset'])
const store = useSimulationStore()
</script>

<template>
  <aside class="panel">
    <SimParams :config="store.config" :tick="store.tick" />
    <SimControls
      :is-running="store.isRunning"
      @start="$emit('start')"
      @pause="$emit('pause')"
      @step="$emit('step')"
      @reset="$emit('reset')"
    />
    <CarStatusList :cars="store.cars" />
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
</style>
