<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../store/authStore.js'
import { useSimulationStore } from '../store/simulationStore.js'
import SimulationCanvas from '../components/SimulationCanvas.vue'

const BASE = ''

const router = useRouter()
const authStore = useAuthStore()
const store = useSimulationStore()

const sessions = ref([])
const loading = ref(false)
const selectedId = ref(null)
const ticks = ref([])
const currentIndex = ref(0)
const playing = ref(false)
const speed = ref(1)
let timer = null

async function loadSessions() {
  loading.value = true
  try {
    const res = await fetch(BASE + '/api/replay/sessions').then(r => r.json())
    sessions.value = res.sessions || []
  } catch (e) {
    sessions.value = []
  } finally {
    loading.value = false
  }
}

async function selectSession(sid) {
  playing.value = false
  clearInterval(timer)
  selectedId.value = sid
  try {
    const res = await fetch(BASE + `/api/replay/sessions/${sid}`).then(r => r.json())
    ticks.value = res.ticks || []
    currentIndex.value = 0
    if (ticks.value.length > 0) {
      store.handleStateUpdate(ticks.value[0])
    }
  } catch (e) {
    ticks.value = []
  }
}

function seekTo(index) {
  if (index >= 0 && index < ticks.value.length) {
    currentIndex.value = index
    store.handleStateUpdate(ticks.value[index])
  }
}

function togglePlay() {
  if (playing.value) {
    pause()
  } else {
    play()
  }
}

function play() {
  if (ticks.value.length === 0) return
  if (currentIndex.value >= ticks.value.length - 1) {
    currentIndex.value = 0
    store.handleStateUpdate(ticks.value[0])
  }
  playing.value = true
  const interval = Math.max(50, Math.round(500 / speed.value))
  timer = setInterval(() => {
    if (currentIndex.value < ticks.value.length - 1) {
      currentIndex.value++
      store.handleStateUpdate(ticks.value[currentIndex.value])
    } else {
      pause()
    }
  }, interval)
}

function pause() {
  playing.value = false
  clearInterval(timer)
  timer = null
}

watch(speed, () => {
  if (playing.value) {
    pause()
    play()
  }
})

onBeforeUnmount(() => {
  clearInterval(timer)
})

function logout() {
  authStore.logout()
  router.push('/login')
}

function fmtTime(ts) {
  return new Date(ts).toLocaleString('zh-CN')
}
</script>

<template>
  <div class="replay-page">
    <header class="topbar">
      <div class="brand">
        <h1>变电站巡检仿真系统</h1>
        <span class="tag">回放</span>
      </div>
      <div class="user-area">
        <span class="user-name">{{ authStore.username }}</span>
        <button class="btn" @click="router.push('/user')">仿真</button>
        <button class="btn logout" @click="logout">退出</button>
      </div>
    </header>

    <div class="main">
      <!-- 左侧：录制列表 -->
      <aside class="sidebar">
        <div class="sidebar-header">
          <h3>录制列表</h3>
          <button class="btn sm" @click="loadSessions">刷新</button>
        </div>
        <div v-if="loading" class="loading">加载中...</div>
        <div v-else-if="sessions.length === 0" class="empty">暂无录制</div>
        <div v-else class="session-list">
          <div
            v-for="s in sessions" :key="s.sessionId"
            class="session-item"
            :class="{ active: s.sessionId === selectedId }"
            @click="selectSession(s.sessionId)"
          >
            <div class="session-id">{{ s.sessionId }}</div>
            <div class="session-meta">
              {{ s.tickCount }} ticks | {{ s.config.mapWidth || '?' }}x{{ s.config.mapHeight || '?' }}
            </div>
            <div class="session-time">{{ fmtTime(s.startTime) }}</div>
          </div>
        </div>
      </aside>

      <!-- 右侧：回放区 -->
      <section class="viewer">
        <div v-if="!selectedId" class="placeholder">请从左侧选择一个录制</div>
        <template v-else>
          <div class="canvas-wrap">
            <SimulationCanvas />
          </div>

          <!-- 控制栏 -->
          <div class="controls">
            <button class="btn ctrl-btn" @click="togglePlay">
              {{ playing ? '⏸ 暂停' : '▶ 播放' }}
            </button>

            <div class="progress-wrap">
              <span class="tick-label">{{ currentIndex + 1 }} / {{ ticks.length }}</span>
              <input
                type="range"
                min="0"
                :max="ticks.length - 1"
                :value="currentIndex"
                @input="seekTo(Number($event.target.value))"
                class="slider"
              />
            </div>

            <div class="speed-select">
              <span class="speed-label">速度</span>
              <select v-model.number="speed">
                <option :value="0.5">0.5x</option>
                <option :value="1">1x</option>
                <option :value="2">2x</option>
                <option :value="4">4x</option>
              </select>
            </div>
          </div>
        </template>
      </section>
    </div>
  </div>
</template>

<style scoped>
.replay-page {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: #161616;
}
.topbar {
  display: flex; align-items: center; justify-content: space-between;
  height: 52px; padding: 0 20px;
  background: #222; border-bottom: 1px solid #333; flex-shrink: 0;
}
.brand { display: flex; align-items: center; gap: 10px; }
.brand h1 { font-size: 16px; color: #fff; }
.tag { font-size: 11px; padding: 2px 8px; border-radius: 4px; background: rgba(129,199,132,0.2); color: #81c784; }
.user-area { display: flex; align-items: center; gap: 12px; }
.user-name { font-size: 13px; color: #aaa; }
.btn {
  background: #3a3a3a; color: #eee; border: 1px solid #4a4a4a;
  border-radius: 6px; padding: 6px 14px; font-size: 13px; cursor: pointer;
}
.btn:hover { background: #4a4a4a; }
.btn.sm { padding: 4px 10px; font-size: 12px; }
.btn.logout { color: #ff8a65; border-color: #ff8a65; background: transparent; }
.main { flex: 1; display: flex; overflow: hidden; }
.sidebar {
  width: 260px; background: #1a1a1a; border-right: 1px solid #333;
  display: flex; flex-direction: column; flex-shrink: 0;
}
.sidebar-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 14px 16px 10px;
}
.sidebar-header h3 { font-size: 13px; color: #ccc; }
.loading, .empty { text-align: center; color: #888; padding: 40px 0; font-size: 13px; }
.session-list { flex: 1; overflow-y: auto; }
.session-item {
  padding: 12px 16px; cursor: pointer; border-bottom: 1px solid #2a2a2a; transition: background 0.15s;
}
.session-item:hover { background: rgba(255,255,255,0.03); }
.session-item.active { background: rgba(79,195,247,0.08); border-left: 3px solid #4fc3f7; }
.session-id { font-size: 13px; color: #eee; font-family: monospace; margin-bottom: 4px; }
.session-meta { font-size: 11px; color: #888; }
.session-time { font-size: 10px; color: #555; margin-top: 2px; }
.viewer { flex: 1; display: flex; flex-direction: column; }
.placeholder { flex: 1; display: flex; align-items: center; justify-content: center; color: #666; font-size: 14px; }
.canvas-wrap {
  flex: 1; padding: 12px; display: flex; align-items: center; justify-content: center;
  overflow: hidden;
}
.controls {
  height: 64px; background: #1e1e1e; border-top: 1px solid #333;
  display: flex; align-items: center; padding: 0 20px; gap: 20px; flex-shrink: 0;
}
.ctrl-btn {
  padding: 8px 20px; font-size: 14px; background: #4fc3f7; border-color: #4fc3f7;
  color: #10242e; font-weight: 600; min-width: 90px;
}
.ctrl-btn:hover { background: #6fcef9; }
.progress-wrap { flex: 1; display: flex; align-items: center; gap: 12px; }
.tick-label { font-size: 12px; color: #aaa; min-width: 80px; text-align: right; font-family: monospace; }
.slider { flex: 1; accent-color: #4fc3f7; height: 6px; }
.speed-select { display: flex; align-items: center; gap: 8px; }
.speed-label { font-size: 12px; color: #888; }
.speed-select select {
  background: #2a2a2a; border: 1px solid #444; border-radius: 6px;
  color: #eee; padding: 4px 8px; font-size: 13px;
}
</style>
