<script setup>
import { ref, watch, onBeforeUnmount, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../store/authStore.js'
import { useSimulationStore } from '../store/simulationStore.js'
import SimulationCanvas from '../components/SimulationCanvas.vue'
import CarStatusList from '../components/CarStatusList.vue'
import { api } from '../utils/api.js'

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

const selectedSession = computed(() => sessions.value.find(s => s.sessionId === selectedId.value))

async function loadSessions() {
  loading.value = true
  try {
    const res = await api.listReplaySessions()
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

  // 清除旧仿真状态
  store.cars = []
  store.mapView = []
  store.mapBlock = []
  store.tick = 0
  store.sessionId = null
  store.isRunning = false

  try {
    const res = await api.getReplaySession(sid)
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
  playing.value ? pause() : play()
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

function stepForward() {
  if (currentIndex.value < ticks.value.length - 1) {
    currentIndex.value++
    store.handleStateUpdate(ticks.value[currentIndex.value])
  }
}

function stepBackward() {
  if (currentIndex.value > 0) {
    currentIndex.value--
    store.handleStateUpdate(ticks.value[currentIndex.value])
  }
}

watch(speed, () => {
  if (playing.value) { pause(); play() }
})

onBeforeUnmount(() => { clearInterval(timer) })

onMounted(() => {
  store.cars = []
  store.mapView = []
  store.mapBlock = []
  store.tick = 0
  loadSessions()
})

function logout() {
  authStore.logout()
  router.push('/login')
}

function backToSim() {
  pause()
  router.push('/user')
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
      <div class="actions">
        <button v-if="selectedId" class="btn" @click="selectedId = null">← 录制列表</button>
        <button class="btn" @click="backToSim">仿真页面</button>
        <span class="user-name">{{ authStore.username }}</span>
        <button class="btn logout" @click="logout">退出登录</button>
      </div>
    </header>

    <!-- 未选择录制：列表视图 -->
    <div v-if="!selectedId" class="list-view">
      <div class="list-header">
        <h2>录制列表</h2>
        <div class="list-header-actions">
          <span class="count">{{ sessions.length }} 个录制</span>
          <button class="btn" @click="loadSessions">刷新</button>
        </div>
      </div>

      <div v-if="loading" class="loading">加载中...</div>
      <div v-else-if="sessions.length === 0" class="empty">
        <div class="empty-icon">📼</div>
        <div class="empty-text">暂无录制</div>
        <div class="empty-hint">运行仿真后会自动生成录制</div>
      </div>

      <div v-else class="session-grid">
        <div
          v-for="s in sessions" :key="s.sessionId"
          class="session-card"
          @click="selectSession(s.sessionId)"
        >
          <div class="card-header">
            <span class="session-id">{{ s.sessionId }}</span>
            <span class="tick-badge">{{ s.tickCount }} ticks</span>
          </div>
          <div class="card-body">
            <div class="stat">
              <span class="stat-label">地图尺寸</span>
              <span class="stat-val">{{ s.config.mapWidth || '?' }} × {{ s.config.mapHeight || '?' }}</span>
            </div>
            <div class="stat">
              <span class="stat-label">小车数量</span>
              <span class="stat-val">{{ s.config.carCount || '?' }} 辆</span>
            </div>
            <div class="stat">
              <span class="stat-label">录制时间</span>
              <span class="stat-val">{{ fmtTime(s.startTime) }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 已选择录制：仿真视图布局 -->
    <div v-else class="main">
      <main class="canvas-wrap">
        <SimulationCanvas />
      </main>

      <aside class="panel">
        <!-- 会话信息 -->
        <section class="info-section" v-if="selectedSession">
          <h3>录制：{{ selectedSession.sessionId }}</h3>
          <div class="info-grid">
            <div class="info-item">
              <span>地图</span>
              <b>{{ selectedSession.config.mapWidth || '?' }}×{{ selectedSession.config.mapHeight || '?' }}</b>
            </div>
            <div class="info-item">
              <span>总 Tick</span>
              <b>{{ ticks.length }}</b>
            </div>
            <div class="info-item">
              <span>当前</span>
              <b>{{ currentIndex + 1 }} / {{ ticks.length }}</b>
            </div>
            <div class="info-item">
              <span>小车</span>
              <b>{{ store.cars.length }} 辆</b>
            </div>
          </div>
        </section>

        <!-- 回放控制 -->
        <section class="control-section">
          <div class="control-row">
            <button class="ctrl-btn" @click="stepBackward" :disabled="currentIndex <= 0">⏮</button>
            <button class="ctrl-btn play-btn" @click="togglePlay">
              {{ playing ? '⏸ 暂停' : '▶ 播放' }}
            </button>
            <button class="ctrl-btn" @click="stepForward" :disabled="currentIndex >= ticks.length - 1">⏭</button>
          </div>
          <div class="progress-wrap">
            <input
              type="range"
              min="0"
              :max="ticks.length - 1"
              :value="currentIndex"
              @input="seekTo(Number($event.target.value))"
              class="slider"
            />
          </div>
          <div class="speed-row">
            <span class="speed-label">速度</span>
            <select v-model.number="speed" class="speed-select">
              <option :value="0.5">0.5x</option>
              <option :value="1">1x</option>
              <option :value="2">2x</option>
              <option :value="4">4x</option>
              <option :value="8">8x</option>
            </select>
          </div>
        </section>

        <!-- 小车状态列表（只读） -->
        <CarStatusList :cars="store.cars" readonly />
      </aside>
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

/* ===== 顶栏 ===== */
.topbar {
  display: flex; align-items: center; justify-content: space-between;
  height: 52px; padding: 0 16px;
  background: #222; border-bottom: 1px solid #333; flex-shrink: 0;
}
.brand { display: flex; align-items: center; gap: 10px; }
.brand h1 { font-size: 16px; color: #fff; }
.tag { font-size: 11px; padding: 2px 8px; border-radius: 4px; background: rgba(129,199,132,0.2); color: #81c784; }
.actions { display: flex; align-items: center; gap: 10px; }
.user-name { font-size: 12px; color: #888; }
.btn {
  background: #3a3a3a; color: #eee; border: 1px solid #4a4a4a;
  border-radius: 6px; padding: 6px 14px; font-size: 13px; cursor: pointer;
}
.btn:hover { background: #4a4a4a; }
.btn.logout { color: #ff8a65; border-color: #ff8a65; background: transparent; }

/* ===== 列表视图 ===== */
.list-view {
  flex: 1; overflow-y: auto; padding: 30px 40px;
}
.list-header {
  display: flex; align-items: center; gap: 14px; margin-bottom: 24px;
}
.list-header h2 { font-size: 20px; color: #fff; }
.list-header-actions { display: flex; align-items: center; gap: 12px; margin-left: auto; }
.count { font-size: 13px; color: #888; padding: 3px 10px; background: #262626; border-radius: 10px; }
.loading { text-align: center; padding: 60px 0; color: #888; }
.empty { text-align: center; padding: 80px 20px; }
.empty-icon { font-size: 56px; margin-bottom: 16px; }
.empty-text { font-size: 17px; color: #aaa; }
.empty-hint { font-size: 13px; color: #666; margin-top: 8px; }

.session-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}
.session-card {
  background: #1e1e1e; border: 1px solid #333; border-radius: 10px;
  padding: 18px; cursor: pointer; transition: all 0.2s;
}
.session-card:hover {
  border-color: #81c784; background: #222;
  transform: translateY(-2px); box-shadow: 0 4px 16px rgba(129,199,132,0.1);
}
.card-header {
  display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px;
}
.session-id { font-size: 15px; font-weight: 600; color: #e8e8e8; font-family: monospace; }
.tick-badge { font-size: 11px; padding: 2px 8px; border-radius: 4px; background: rgba(129,199,132,0.15); color: #81c784; }
.card-body { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.stat { display: flex; flex-direction: column; gap: 2px; }
.stat-label { font-size: 11px; color: #777; }
.stat-val { font-size: 14px; color: #ddd; font-weight: 600; }

/* ===== 仿真视图 ===== */
.main {
  flex: 1; display: flex; min-height: 0;
}
.canvas-wrap {
  flex: 1; min-width: 0; background: #1a1a1a; position: relative;
}
.panel {
  width: 320px; flex-shrink: 0; background: #1e1e1e;
  border-left: 1px solid #333; overflow-y: auto;
  padding: 16px; display: flex; flex-direction: column; gap: 16px;
}

/* 信息 */
.info-section {
  background: #262626; border-radius: 8px; padding: 14px 16px;
}
.info-section h3 { font-size: 13px; color: #81c784; margin-bottom: 10px; word-break: break-all; }
.info-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.info-item { display: flex; flex-direction: column; gap: 2px; }
.info-item span { font-size: 11px; color: #888; }
.info-item b { font-size: 13px; color: #eee; font-weight: 600; }

/* 回放控制 */
.control-section {
  background: #262626; border-radius: 8px; padding: 14px 16px;
  display: flex; flex-direction: column; gap: 12px;
}
.control-row { display: flex; gap: 8px; justify-content: center; }
.ctrl-btn {
  padding: 8px 16px; background: #2a2a2a; color: #ccc;
  border: none; border-radius: 6px; font-size: 14px; cursor: pointer; transition: all 0.15s;
}
.ctrl-btn:hover:not(:disabled) { filter: brightness(1.2); }
.ctrl-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.play-btn { background: #43a047; color: #fff; font-weight: 600; min-width: 100px; }
.progress-wrap { width: 100%; }
.slider { width: 100%; accent-color: #81c784; height: 6px; cursor: pointer; }
.speed-row { display: flex; align-items: center; gap: 8px; justify-content: center; }
.speed-label { font-size: 12px; color: #888; }
.speed-select {
  background: #2a2a2a; border: 1px solid #444; border-radius: 6px;
  color: #eee; padding: 4px 8px; font-size: 13px; cursor: pointer;
}
</style>
