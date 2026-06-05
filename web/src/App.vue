<script setup>
import { ref } from 'vue'
import { useSimulationStore } from './store/simulationStore.js'
import { useWebSocket } from './composables/useWebSocket.js'
import { COMMANDS } from './utils/constants.js'
import TopBar from './components/TopBar.vue'
import SimulationCanvas from './components/SimulationCanvas.vue'
import ControlPanel from './components/ControlPanel.vue'
import ConfigDialog from './components/ConfigDialog.vue'

const store = useSimulationStore()
const { connected, sendCommand } = useWebSocket(store)

const configVisible = ref(false)

function onOpenConfig() {
  configVisible.value = true
}

function onConfigConfirm(form) {
  sendCommand(COMMANDS.SET_CONFIG, form)
  configVisible.value = false
}

function onConfigCancel() {
  configVisible.value = false
}

function onReset() {
  if (window.confirm('确定要重置仿真吗？')) {
    sendCommand(COMMANDS.RESET)
    store.setRunning(false)
  }
}

function onStart() {
  sendCommand(COMMANDS.RESUME)
  store.setRunning(true)
}

function onPause() {
  sendCommand(COMMANDS.PAUSE)
  store.setRunning(false)
}

function onStep() {
  sendCommand(COMMANDS.STEP_ONCE)
}
</script>

<template>
  <div class="app">
    <TopBar
      title="变电站巡检仿真系统"
      :connected="connected"
      @open-config="onOpenConfig"
      @reset="onReset"
    />
    <div class="main">
      <main class="canvas-wrap">
        <SimulationCanvas />
      </main>
      <ControlPanel
        @start="onStart"
        @pause="onPause"
        @step="onStep"
        @reset="onReset"
      />
    </div>
    <ConfigDialog
      :visible="configVisible"
      :config="store.config"
      @confirm="onConfigConfirm"
      @cancel="onConfigCancel"
    />
  </div>
</template>

<style>
/* 全局样式（非 scoped） */
* {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}
html,
body,
#app {
  height: 100%;
}
body {
  font-family: -apple-system, 'Segoe UI', 'Microsoft YaHei', sans-serif;
  background: #161616;
  color: #e8e8e8;
  -webkit-font-smoothing: antialiased;
}
button {
  font-family: inherit;
  cursor: pointer;
}
</style>

<style scoped>
.app {
  display: flex;
  flex-direction: column;
  height: 100vh;
}
.main {
  flex: 1;
  display: flex;
  min-height: 0;
}
.canvas-wrap {
  flex: 1;
  min-width: 0;
  background: #1a1a1a;
  position: relative;
}
</style>
