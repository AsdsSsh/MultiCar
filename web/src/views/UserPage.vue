<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../store/authStore.js'
import { useSimulationStore } from '../store/simulationStore.js'
import { useWebSocket } from '../composables/useWebSocket.js'
import { api } from '../utils/api.js'
import { COMMANDS, WS_URL, ALGORITHMS, CAR_COLORS } from '../utils/constants.js'
import SimulationCanvas from '../components/SimulationCanvas.vue'
import CarStatusList from '../components/CarStatusList.vue'

const router = useRouter()
const authStore = useAuthStore()
const store = useSimulationStore()

// ===== 状态 =====
const viewMode = ref('list')  // 'list' | 'simulation'
const savedMaps = ref([])
const mapsLoading = ref(true)
const selectedMap = ref(null)

// ===== 增加小车弹窗 =====
const showAddCarDialog = ref(false)
const pickCarPosMode = ref(false)    // 进入点击地图选位置模式（增加小车）
const pickedPos = ref(null)          // 用户在地图上点击选中的坐标 {x, y}
const pickedPosError = ref('')      // 位置错误提示（如：点击了障碍物）
const addCarAutoMode = ref(true)    // 默认自动选择位置

// ===== 移动小车 =====
const moveCarMode = ref(false)      // 进入移动小车选位置模式
const moveCarId = ref(null)         // 当前正在移动的小车ID

// ===== WebSocket 连接 =====
// 地图列表更新回调：当配置员保存新地图时自动刷新
function onMapListUpdated() {
  console.log('[UserPage] 收到 MAP_LIST_UPDATED，刷新地图列表')
  loadSavedMaps()
}

const wsUrl = WS_URL + '?token=' + authStore.token
const { connected, sendCommand } = useWebSocket(store, wsUrl, {
  onMapListUpdated
})

// ===== 加载地图列表 =====
async function loadSavedMaps() {
  mapsLoading.value = true
  try {
    const res = await api.listMaps()
    savedMaps.value = res.maps || []
  } catch (e) {
    savedMaps.value = []
  } finally {
    mapsLoading.value = false
  }
}

onMounted(() => {
  loadSavedMaps()
})

// ===== 选择地图开始探索 =====
function selectMap(map) {
  selectedMap.value = map

  // 先停旧 session，再生成新 sessionId
  if (store.sessionId) sendCommand('STOP')
  const sessionId = crypto.randomUUID ? crypto.randomUUID().substring(0, 8) : Date.now().toString(36)

  const config = {
    sessionId,
    mapWidth: map.mapWidth || 40,
    mapHeight: map.mapHeight || 30,
    carCount: map.carPositions?.length || map.carCount || 5,
    obstacleDensity: map.obstacleDensity || 0,
    algorithm: map.algorithm || 'A_STAR',
    customObstacles: map.obstacles || [],
    carPositions: map.carPositions || []
  }

  // 清除旧地图数据，避免新地图加载前显示残留
  store.cars = []
  store.mapView = []
  store.mapBlock = []
  store.tick = 0

  store.config = {
    ...store.config,
    mapWidth: config.mapWidth,
    mapHeight: config.mapHeight,
    carCount: config.carCount,
    obstacleDensity: config.obstacleDensity,
    algorithm: config.algorithm
  }
  store.sessionId = sessionId

  sendCommand(COMMANDS.SET_CONFIG, config)
  store.setRunning(false)
  viewMode.value = 'simulation'
  loadSavedMaps()
}

// ===== 返回地图列表 =====
function backToList() {
  // 先停止仿真
  sendCommand('STOP')
  store.setRunning(false)
  viewMode.value = 'list'
  selectedMap.value = null
}

// ===== 控制命令 =====
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

function onReset() {
  if (window.confirm('确定要重置仿真吗？')) {
    sendCommand(COMMANDS.RESET)
    store.setRunning(false)
  }
}

function onAddCar() {
  // 重置状态，默认使用自动模式
  pickedPos.value = null
  pickedPosError.value = ''
  addCarAutoMode.value = true
  pickCarPosMode.value = false
  showAddCarDialog.value = true
}

// 点击地图选择位置回调（增加小车 / 移动小车共用）
function onCellPicked(cell) {
  const { x, y } = cell

  // === 移动小车模式 ===
  if (moveCarMode.value) {
    // 检查是否是障碍物
    const row = store.mapBlock[y]
    if (row && row[x]) {
      alert('该位置是障碍物，请换个位置')
      return
    }
    // 检查是否被其他小车占据
    const carHere = store.cars.find(c => c.carId !== moveCarId.value && c.x === x && c.y === y)
    if (carHere) {
      alert(`位置 (${x}, ${y}) 已有小车 ${carHere.carId}，请换个位置`)
      return
    }
    sendCommand(COMMANDS.MOVE_CAR, { carId: moveCarId.value, x, y })
    moveCarMode.value = false
    moveCarId.value = null
    return
  }

  // === 增加小车模式 ===
  if (!pickCarPosMode.value) return

  // 检查是否是障碍物
  const row2 = store.mapBlock[y]
  if (row2 && row2[x]) {
    pickedPosError.value = `位置 (${x}, ${y}) 是障碍物，请换个位置点击`
    return
  }

  // 检查是否有其他小车在此位置
  const carHere2 = store.cars.find(c => c.x === x && c.y === y)
  if (carHere2) {
    pickedPosError.value = `位置 (${x}, ${y}) 已有小车 ${carHere2.carId}，请换个位置`
    return
  }

  pickedPosError.value = ''
  pickedPos.value = { x, y }
  addCarAutoMode.value = false  // 切换到手动模式
  pickCarPosMode.value = false  // 退出选位置模式
  showAddCarDialog.value = true // 重新打开弹窗让用户确认
}

// 进入选位置模式：关闭弹窗，让用户直接点击地图
function startPickMode() {
  addCarAutoMode.value = false
  pickedPos.value = null
  pickedPosError.value = ''
  pickCarPosMode.value = true
  showAddCarDialog.value = false  // 关键：关闭弹窗遮罩，让点击穿透到地图
}

// 退出选位置模式
function exitPickMode() {
  pickCarPosMode.value = false
}

function confirmAddCar() {
  const data = addCarAutoMode.value
    ? {} // 自动模式：不传坐标，后端自动分配
    : { x: pickedPos.value?.x, y: pickedPos.value?.y }
  sendCommand(COMMANDS.ADD_CAR, data)
  pickCarPosMode.value = false
  showAddCarDialog.value = false
}

function cancelAddCar() {
  pickCarPosMode.value = false
  moveCarMode.value = false
  moveCarId.value = null
  showAddCarDialog.value = false
}

// ===== 删除小车 =====
function onDeleteCar(carId) {
  if (!window.confirm(`确认删除小车 ${carId} 吗？此操作仅在运行时生效，不影响地图原始配置。`)) return
  sendCommand(COMMANDS.DELETE_CAR, { carId })
}

// ===== 移动小车（进入点击地图选位置模式） =====
function onMoveCar(carId) {
  // 先退出其他模式
  pickCarPosMode.value = false
  showAddCarDialog.value = false
  // 进入移动模式
  moveCarId.value = carId
  moveCarMode.value = true
}

// 取消移动模式
function cancelMoveCar() {
  moveCarMode.value = false
  moveCarId.value = null
}

function logout() {
  authStore.logout()
  router.push('/login')
}

// ===== 辅助 =====
function formatTime(ts) {
  if (!ts) return ''
  return new Date(ts).toLocaleString('zh-CN')
}
</script>

<template>
  <div class="user-page">
    <!-- 顶栏 -->
    <header class="topbar">
      <div class="brand">
        <h1>变电站巡检仿真系统</h1>
        <span class="role-badge user">用户</span>
        <span class="conn" :class="{ on: connected }">
          <span class="dot"></span>{{ connected ? '已连接' : '未连接' }}
        </span>
      </div>
      <div class="actions">
        <button v-if="viewMode === 'simulation'" class="btn" @click="backToList">← 返回地图列表</button>
        <span class="user-name">{{ authStore.username }}</span>
        <button class="btn logout" @click="logout">退出登录</button>
      </div>
    </header>

    <!-- 地图列表视图 -->
    <div v-if="viewMode === 'list'" class="list-view">
      <div class="list-header">
        <h2>选择地图开始探索</h2>
        <span class="map-count">共 {{ savedMaps.length }} 张地图</span>
      </div>

      <div v-if="mapsLoading" class="loading">加载地图列表...</div>

      <div v-else-if="savedMaps.length === 0" class="empty">
        <div class="empty-icon">🗺️</div>
        <div class="empty-text">暂无可用地图</div>
        <div class="empty-hint">请联系配置员创建地图后刷新页面</div>
      </div>

      <div v-else class="map-grid">
        <div
          v-for="map in savedMaps"
          :key="map.id"
          class="map-card"
          @click="selectMap(map)"
        >
          <div class="map-card-header">
            <span class="map-name">{{ map.name }}</span>
            <span class="map-algo">{{ ALGORITHMS.find(a => a.value === map.algorithm)?.label || map.algorithm || 'A*' }}</span>
          </div>
          <div class="map-card-body">
            <div class="map-stat">
              <span class="stat-label">地图尺寸</span>
              <span class="stat-val">{{ map.mapWidth }} × {{ map.mapHeight }}</span>
            </div>
            <div class="map-stat">
              <span class="stat-label">小车数量</span>
              <span class="stat-val">{{ map.carPositions?.length || map.carCount || 0 }} 辆</span>
            </div>
            <div class="map-stat">
              <span class="stat-label">障碍物</span>
              <span class="stat-val">{{ map.obstacles?.length || 0 }} 个</span>
            </div>
            <div class="map-stat">
              <span class="stat-label">障碍密度</span>
              <span class="stat-val">{{ Math.round((map.obstacleDensity || 0) * 100) }}%</span>
            </div>
          </div>
          <div class="map-card-footer">
            <span class="map-created-at" v-if="map.createdAt">{{ formatTime(map.createdAt) }}</span>
            <span class="map-creator" v-if="map.createdBy">创建者: {{ map.createdBy }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 仿真视图 -->
    <div v-if="viewMode === 'simulation'" class="main">
      <main class="canvas-wrap">
        <SimulationCanvas :pickMode="pickCarPosMode || moveCarMode" @cell-pick="onCellPicked" />
        <!-- 移动小车提示条 -->
        <div v-if="moveCarMode" class="move-indicator">
          <span>📌 点击地图空白位置为 {{ moveCarId }} 选择新位置</span>
          <button @click="cancelMoveCar">✕ 取消</button>
        </div>
      </main>

      <aside class="panel">
        <!-- 当前地图信息 -->
        <section class="info-section" v-if="selectedMap">
          <h3>当前地图：{{ selectedMap.name }}</h3>
          <div class="info-grid">
            <div class="info-item">
              <span>尺寸</span>
              <b>{{ selectedMap.mapWidth }}×{{ selectedMap.mapHeight }}</b>
            </div>
            <div class="info-item">
              <span>算法</span>
              <b>{{ ALGORITHMS.find(a => a.value === selectedMap.algorithm)?.label || selectedMap.algorithm || 'A*' }}</b>
            </div>
            <div class="info-item">
              <span>初始小车</span>
              <b>{{ selectedMap.carPositions?.length || selectedMap.carCount || 0 }}</b>
            </div>
            <div class="info-item">
              <span>障碍物</span>
              <b>{{ selectedMap.obstacles?.length || 0 }} 个</b>
            </div>
          </div>
        </section>

        <!-- 仿真参数 -->
        <section class="params-section">
          <div class="param-item">
            <span class="param-label">Tick</span>
            <span class="param-val">{{ store.tick }}</span>
          </div>
          <div class="param-item">
            <span class="param-label">状态</span>
            <span class="param-val" :class="{ running: store.isRunning }">
              {{ store.isRunning ? '运行中' : '已暂停' }}
            </span>
          </div>
          <div class="param-item">
            <span class="param-label">小车</span>
            <span class="param-val">{{ store.cars.length }} 辆</span>
          </div>
        </section>

        <!-- 控制按钮 -->
        <section class="control-section">
          <div class="control-row">
            <button class="ctrl-btn start" :disabled="store.isRunning" @click="onStart">
              ▶ 开始
            </button>
            <button class="ctrl-btn pause" :disabled="!store.isRunning" @click="onPause">
              ⏸ 暂停
            </button>
          </div>
          <div class="control-row">
            <button class="ctrl-btn step" @click="onStep">
              ⏭ 单步
            </button>
            <button class="ctrl-btn reset" @click="onReset">
              ↺ 重置
            </button>
          </div>
          <button class="ctrl-btn add-car primary" @click="onAddCar">
            ＋ 增加小车
          </button>
        </section>

        <!-- 小车状态列表 -->
        <CarStatusList :cars="store.cars" @delete-car="onDeleteCar" @move-car="onMoveCar" />
      </aside>
    </div>

    <!-- 增加小车位置选择弹窗 -->
    <div v-if="showAddCarDialog" class="dialog-overlay" @click.self="cancelAddCar">
      <div class="dialog-card">
        <div class="dialog-header">
          <h3>＋ 增加小车</h3>
          <button class="dialog-close" @click="cancelAddCar">✕</button>
        </div>
        <div class="dialog-body">
          <p class="dialog-desc">选择新小车在地图上的初始位置</p>

          <!-- 模式切换 -->
          <div class="pos-mode-row">
            <button
              :class="['pos-mode-btn', { active: addCarAutoMode }]"
              @click="exitPickMode(); addCarAutoMode = true"
            >
              🤖 自动选择（避开障碍物）
            </button>
            <button
              :class="['pos-mode-btn', { active: !addCarAutoMode }]"
              @click="startPickMode"
            >
              🖱 点击地图选择位置
            </button>
          </div>

          <!-- 点击地图选位置提示 -->
          <div v-if="!addCarAutoMode" class="pick-section">
            <p v-if="!pickedPos" class="pick-hint">
              👆 请在地图上点击空白位置来选择新小车初始位置<br/>
              <small>（点击地图位置后，可在弹窗中确认或取消）</small>
            </p>

            <!-- 位置错误提示 -->
            <div v-if="pickedPosError" class="pick-error">
              ⚠ {{ pickedPosError }}
            </div>

            <!-- 已选中位置 -->
            <div v-if="pickedPos" class="picked-info">
              <span class="picked-icon">✅</span>
              已选择位置：<b>({{ pickedPos.x }}, {{ pickedPos.y }})</b>
            </div>
          </div>
          <p v-else class="pos-hint auto-hint">
            后端将自动为小车选择一个避开已有小车和障碍物的位置
          </p>
        </div>
        <div class="dialog-footer">
          <button class="dlg-btn cancel" @click="cancelAddCar">取消</button>
          <button
            class="dlg-btn confirm"
            :disabled="!addCarAutoMode && !pickedPos"
            @click="confirmAddCar"
          >
            {{ addCarAutoMode ? '自动添加'
              : pickedPos ? `在 (${pickedPos.x}, ${pickedPos.y}) 添加`
              : '请先点击地图选择位置' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.user-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
}
.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 52px;
  padding: 0 16px;
  background: #222;
  border-bottom: 1px solid #333;
  flex-shrink: 0;
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
}
.brand h1 {
  font-size: 16px;
  font-weight: 600;
  color: #fff;
}
.role-badge {
  font-size: 11px;
  padding: 2px 10px;
  border-radius: 10px;
  font-weight: 600;
}
.role-badge.user { background: rgba(79, 195, 247, 0.2); color: #4fc3f7; }
.conn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: #e57373;
  padding: 2px 8px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.06);
}
.conn.on { color: #81c784; }
.dot {
  width: 7px; height: 7px;
  border-radius: 50%;
  background: currentColor;
}
.actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.user-name {
  font-size: 12px;
  color: #888;
}
.btn {
  background: #3a3a3a;
  color: #eee;
  border: 1px solid #4a4a4a;
  border-radius: 6px;
  padding: 6px 14px;
  font-size: 13px;
  cursor: pointer;
}
.btn:hover { background: #4a4a4a; }
.btn.logout { color: #ff8a65; border-color: #ff8a65; background: transparent; }

/* ===== 地图列表视图 ===== */
.list-view {
  flex: 1;
  overflow-y: auto;
  padding: 30px 40px;
}
.list-header {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 24px;
}
.list-header h2 {
  font-size: 20px;
  color: #fff;
}
.map-count {
  font-size: 13px;
  color: #888;
  padding: 3px 10px;
  background: #262626;
  border-radius: 10px;
}
.loading {
  text-align: center;
  padding: 60px 0;
  font-size: 15px;
  color: #888;
}
.empty {
  text-align: center;
  padding: 80px 20px;
}
.empty-icon { font-size: 56px; margin-bottom: 16px; }
.empty-text { font-size: 17px; color: #aaa; }
.empty-hint { font-size: 13px; color: #666; margin-top: 8px; }

.map-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}
.map-card {
  background: #1e1e1e;
  border: 1px solid #333;
  border-radius: 10px;
  padding: 18px;
  cursor: pointer;
  transition: all 0.2s;
}
.map-card:hover {
  border-color: #4fc3f7;
  background: #222;
  transform: translateY(-2px);
  box-shadow: 0 4px 16px rgba(79,195,247,0.1);
}
.map-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}
.map-name {
  font-size: 16px;
  font-weight: 600;
  color: #e8e8e8;
}
.map-algo {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 4px;
  background: rgba(79,195,247,0.15);
  color: #4fc3f7;
}
.map-card-body {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-bottom: 12px;
}
.map-stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.stat-label { font-size: 11px; color: #777; }
.stat-val { font-size: 14px; color: #ddd; font-weight: 600; }
.map-card-footer {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: #555;
  padding-top: 10px;
  border-top: 1px solid #2a2a2a;
}

/* ===== 仿真视图 ===== */
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

/* 移动小车提示条 */
.move-indicator {
  position: absolute;
  top: 12px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 18px;
  background: rgba(255, 152, 0, 0.15);
  border: 1px solid #ff9800;
  border-radius: 20px;
  font-size: 13px;
  color: #ffcc80;
  z-index: 10;
  white-space: nowrap;
}
.move-indicator button {
  background: rgba(229, 115, 115, 0.3);
  border: 1px solid #e57373;
  border-radius: 4px;
  color: #ef9a9a;
  font-size: 11px;
  padding: 2px 8px;
  cursor: pointer;
  white-space: nowrap;
}
.move-indicator button:hover {
  background: rgba(229, 115, 115, 0.5);
}

.panel {
  width: 320px;
  flex-shrink: 0;
  background: #1e1e1e;
  border-left: 1px solid #333;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  padding: 16px;
  gap: 16px;
}

/* 地图信息 */
.info-section {
  background: #262626;
  border-radius: 8px;
  padding: 14px 16px;
}
.info-section h3 {
  font-size: 13px;
  color: #4fc3f7;
  margin-bottom: 10px;
}
.info-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.info-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.info-item span {
  font-size: 11px;
  color: #888;
}
.info-item b {
  font-size: 13px;
  color: #eee;
  font-weight: 600;
}

/* 参数 */
.params-section {
  display: flex;
  gap: 12px;
  background: #262626;
  border-radius: 8px;
  padding: 12px 16px;
}
.param-item {
  flex: 1;
  text-align: center;
}
.param-label {
  display: block;
  font-size: 10px;
  color: #888;
  margin-bottom: 4px;
}
.param-val {
  font-size: 15px;
  font-weight: 700;
  color: #eee;
}
.param-val.running { color: #81c784; }

/* 控制按钮 */
.control-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.control-row {
  display: flex;
  gap: 8px;
}
.ctrl-btn {
  flex: 1;
  padding: 10px 0;
  border: none;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  background: #2a2a2a;
  color: #ccc;
  transition: all 0.15s;
}
.ctrl-btn:hover:not(:disabled) { filter: brightness(1.2); }
.ctrl-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.ctrl-btn.start { background: #43a047; color: #fff; }
.ctrl-btn.pause { background: #ef6c00; color: #fff; }
.ctrl-btn.step { background: #2a2a2a; color: #4fc3f7; }
.ctrl-btn.reset { background: #2a2a2a; color: #e57373; }
.ctrl-btn.add-car { background: #ff9800; color: #fff; }
.ctrl-btn.primary { font-weight: 700; }

/* ===== 增加小车弹窗 ===== */
.dialog-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}
.dialog-card {
  background: #222;
  border: 1px solid #444;
  border-radius: 12px;
  width: 420px;
  max-width: 90vw;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.5);
}
.dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid #333;
}
.dialog-header h3 {
  font-size: 16px;
  color: #ff9800;
  margin: 0;
}
.dialog-close {
  background: none;
  border: none;
  color: #888;
  font-size: 18px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 4px;
}
.dialog-close:hover { background: #333; color: #fff; }
.dialog-body { padding: 20px; }
.dialog-desc {
  font-size: 13px;
  color: #aaa;
  margin: 0 0 16px 0;
}
.pos-mode-row {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}
.pos-mode-btn {
  flex: 1;
  padding: 10px 12px;
  background: #2a2a2a;
  border: 1px solid #444;
  border-radius: 8px;
  color: #ccc;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.15s;
  text-align: center;
}
.pos-mode-btn:hover { background: #333; }
.pos-mode-btn.active {
  border-color: #ff9800;
  background: rgba(255, 152, 0, 0.12);
  color: #ff9800;
  font-weight: 600;
}
.pos-input-row {
  display: flex;
  gap: 12px;
}
.pos-input-row label {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
/* ===== 点击地图选位置 ===== */
.pick-section {
  margin-top: 4px;
}
.pick-hint {
  font-size: 13px;
  color: #aaa;
  text-align: center;
  padding: 16px 0;
  line-height: 1.6;
}
.pick-hint small {
  font-size: 11px;
  color: #666;
}
.pick-error {
  background: rgba(229, 115, 115, 0.15);
  border: 1px solid #e57373;
  border-radius: 6px;
  padding: 8px 12px;
  font-size: 12px;
  color: #ef9a9a;
  margin-bottom: 8px;
}
.picked-info {
  background: rgba(79, 195, 247, 0.1);
  border: 1px solid #4fc3f7;
  border-radius: 6px;
  padding: 10px 14px;
  font-size: 13px;
  color: #b3e5fc;
  display: flex;
  align-items: center;
  gap: 8px;
}
.picked-icon { font-size: 14px; }
.picked-info b {
  color: #4fc3f7;
  font-size: 14px;
}
.pos-hint {
  font-size: 11px;
  color: #666;
  margin: 8px 0 0 0;
}
.pos-hint.auto-hint {
  color: #81c784;
}
.dlg-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.dialog-footer {
  display: flex;
  gap: 8px;
  padding: 16px 20px;
  border-top: 1px solid #333;
  justify-content: flex-end;
}
.dlg-btn {
  padding: 8px 20px;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  border: none;
  transition: all 0.15s;
}
.dlg-btn.cancel {
  background: #2a2a2a;
  color: #ccc;
}
.dlg-btn.cancel:hover { background: #3a3a3a; }
.dlg-btn.confirm {
  background: #ff9800;
  color: #fff;
}
.dlg-btn.confirm:hover { background: #f57c00; }
</style>
