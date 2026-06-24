<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../store/authStore.js'
import { api } from '../utils/api.js'
import { ALGORITHMS } from '../utils/constants.js'

const router = useRouter()
const authStore = useAuthStore()

const saving = ref(false)
const message = ref('')
const isError = ref(false)
const savedMaps = ref([])
const activeTab = ref('editor') // 'editor' | 'list'

// 地图配置表单
const form = ref({
  mapWidth: 40,
  mapHeight: 30,
  carCount: 5,
  obstacleDensity: 0.2,
  algorithm: 'A_STAR'
})
const mapName = ref('')

// 障碍物编辑器
const obstacles = ref([])  // [{x, y}, ...]
const cellSize = 14  // 像素/格
const gridPanelWidth = ref(560)
const gridPanelHeight = ref(420)
const editTool = ref('obstacle') // 'obstacle' | 'car'
const selectedCarIdx = ref(0)

// 小车位置
const carPositions = ref([]) // [{carId, x, y}, ...]

// 从 carCount 生成初始小车位置
function initCarPositions() {
  const count = form.value.carCount
  const w = form.value.mapWidth
  const h = form.value.mapHeight
  carPositions.value = []
  for (let i = 0; i < count; i++) {
    carPositions.value.push({
      carId: 'Car' + String(i + 1).padStart(3, '0'),
      x: Math.floor(w * 0.1 + i * (w * 0.8 / Math.max(count - 1, 1))),
      y: Math.floor(h * 0.5)
    })
  }
}

function updateCarCount() {
  const count = form.value.carCount
  const current = carPositions.value
  while (current.length < count) {
    const i = current.length
    current.push({
      carId: 'Car' + String(i + 1).padStart(3, '0'),
      x: 0,
      y: 0
    })
  }
  while (current.length > count) {
    current.pop()
  }
  if (selectedCarIdx.value >= count) selectedCarIdx.value = Math.max(0, count - 1)
  // 小车数量变化后重新随机生成障碍物
  randomGenerate()
}

// 网格计算
const gridStyle = computed(() => {
  const w = form.value.mapWidth
  const h = form.value.mapHeight
  return {
    display: 'grid',
    gridTemplateColumns: `repeat(${w}, ${cellSize}px)`,
    gridTemplateRows: `repeat(${h}, ${cellSize}px)`,
    gap: '0px',
    width: w * cellSize + 'px',
    height: h * cellSize + 'px',
    background: '#1a1a1a',
    border: '1px solid #444',
    overflow: 'auto'
  }
})

// 检查某个格子是否是障碍物
function isObstacle(x, y) {
  return obstacles.value.some(o => o.x === x && o.y === y)
}

// 检查某个格子是否有小车
function getCarAt(x, y) {
  return carPositions.value.find(c => c.x === x && c.y === y)
}

// 点击格子
function onCellClick(x, y) {
  if (editTool.value === 'obstacle') {
    // 不能放在小车位置上
    if (getCarAt(x, y)) return
    const idx = obstacles.value.findIndex(o => o.x === x && o.y === y)
    if (idx >= 0) {
      obstacles.value.splice(idx, 1)
    } else {
      obstacles.value.push({ x, y })
    }
  } else if (editTool.value === 'car') {
    // 不能放在障碍物上
    if (isObstacle(x, y)) return
    // 不能放在其他小车位置上
    const otherCar = carPositions.value.find(c => c.carId !== carPositions.value[selectedCarIdx.value]?.carId && c.x === x && c.y === y)
    if (otherCar) return
    if (selectedCarIdx.value < carPositions.value.length) {
      carPositions.value[selectedCarIdx.value] = {
        ...carPositions.value[selectedCarIdx.value],
        x, y
      }
    }
  }
}

// 格子颜色
function cellColor(x, y) {
  const car = getCarAt(x, y)
  if (car) {
    const idx = carPositions.value.indexOf(car)
    const colors = ['#4fc3f7','#ff8a65','#81c784','#ffd54f','#ba68c8','#ce93d8','#90caf9','#ffab91']
    return colors[idx % colors.length]
  }
  if (isObstacle(x, y)) return '#cc4444'
  return '#2a2a2a'
}

// 生成所有格子坐标
function range(n) {
  return Array.from({ length: n }, (_, i) => i)
}

onMounted(async () => {
  initCarPositions()
  randomGenerate()  // 初始时根据默认密度随机生成障碍物
  loadSavedMaps()
})

async function loadSavedMaps() {
  try {
    const res = await api.listMaps()
    savedMaps.value = res.maps || []
  } catch (e) {
    savedMaps.value = []
  }
}

async function onSave() {
  if (!mapName.value.trim()) {
    message.value = '请输入地图名称'
    isError.value = true
    return
  }
  saving.value = true
  message.value = ''
  isError.value = false
  try {
    const payload = {
      name: mapName.value.trim(),
      mapWidth: clamp(form.value.mapWidth, 5, 200),
      mapHeight: clamp(form.value.mapHeight, 5, 200),
      carCount: carPositions.value.length,
      obstacleDensity: clamp(form.value.obstacleDensity, 0, 0.9),
      algorithm: form.value.algorithm,
      obstacles: [...obstacles.value],
      carPositions: carPositions.value.map(c => ({ carId: c.carId, x: c.x, y: c.y }))
    }
    await api.saveMapConfig(payload)
    message.value = '地图已保存并同步给所有用户！'
    isError.value = false
    mapName.value = ''
    initCarPositions()
    randomGenerate()
    await loadSavedMaps()
    activeTab.value = 'list'
  } catch (e) {
    message.value = e.message || '保存失败'
    isError.value = true
  } finally {
    saving.value = false
  }
}

async function deleteMap(map) {
  if (!confirm(`确定要删除地图"${map.name}"吗？`)) return
  try {
    await api.deleteMapConfig(map.id)
    await loadSavedMaps()
  } catch (e) {
    alert('删除失败: ' + (e.message || '未知错误'))
  }
}

function loadMapToEditor(map) {
  mapName.value = map.name
  form.value.mapWidth = map.mapWidth
  form.value.mapHeight = map.mapHeight
  form.value.carCount = map.carCount || map.carPositions?.length || 5
  form.value.obstacleDensity = map.obstacleDensity || 0
  form.value.algorithm = map.algorithm || 'A_STAR'
  obstacles.value = (map.obstacles || []).map(o => ({ x: o.x, y: o.y }))
  carPositions.value = (map.carPositions || []).map(c => ({ carId: c.carId, x: c.x, y: c.y }))
  if (carPositions.value.length === 0) {
    initCarPositions()
  }
  selectedCarIdx.value = 0
  activeTab.value = 'editor'
}

// ===== 随机生成地图 =====
function onMapSizeChange() {
  initCarPositions()
  randomGenerate()
}

function randomGenerate() {
  const w = form.value.mapWidth
  const h = form.value.mapHeight
  const density = form.value.obstacleDensity

  // 保护小车位置及周围3x3区域
  const protectedSet = new Set()
  for (const car of carPositions.value) {
    for (let dx = -1; dx <= 1; dx++) {
      for (let dy = -1; dy <= 1; dy++) {
        const nx = car.x + dx
        const ny = car.y + dy
        if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
          protectedSet.add(nx + ',' + ny)
        }
      }
    }
  }

  // 随机生成障碍物
  const totalCells = w * h
  const targetCount = Math.floor(totalCells * density)
  const newObstacles = []
  const used = new Set()

  let attempts = 0
  const maxAttempts = targetCount * 10 + 1000
  while (newObstacles.length < targetCount && attempts < maxAttempts) {
    attempts++
    const x = Math.floor(Math.random() * w)
    const y = Math.floor(Math.random() * h)
    const key = x + ',' + y
    if (!protectedSet.has(key) && !used.has(key)) {
      used.add(key)
      newObstacles.push({ x, y })
    }
  }

  obstacles.value = newObstacles
  message.value = `已随机生成 ${newObstacles.length} 个障碍物（密度 ${Math.round(density * 100)}%）`
  isError.value = false
}

function clamp(v, min, max) {
  const n = Number(v)
  if (Number.isNaN(n)) return min
  return Math.min(max, Math.max(min, n))
}

function formatTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  return d.toLocaleString('zh-CN')
}

function logout() {
  authStore.logout()
  router.push('/login')
}
</script>

<template>
  <div class="config-page">
    <header class="topbar">
      <div class="brand">
        <h1>变电站巡检仿真系统</h1>
        <span class="role-badge config">配置员</span>
      </div>
      <div class="user-area">
        <span class="user-name">{{ authStore.username }}</span>
        <button class="btn logout" @click="logout">退出登录</button>
      </div>
    </header>

    <div class="content">
      <!-- Tab 切换 -->
      <div class="tab-bar">
        <button :class="{ active: activeTab === 'editor' }" @click="activeTab = 'editor'">地图编辑器</button>
        <button :class="{ active: activeTab === 'list' }" @click="activeTab = 'list'">
          已保存地图 ({{ savedMaps.length }})
        </button>
      </div>

      <!-- 编辑器 -->
      <div v-if="activeTab === 'editor'" class="editor-area">
        <!-- 左侧：配置表单 -->
        <div class="editor-left">
          <h2>地图配置</h2>

          <div v-if="message" class="msg" :class="{ error: isError }">{{ message }}</div>

          <label>
            <span>地图名称</span>
            <input v-model="mapName" placeholder="输入地图名称" />
          </label>

          <div class="form-row">
            <label>
              <span>宽度 (5-200)</span>
              <input type="number" v-model.number="form.mapWidth" min="5" max="200" @change="onMapSizeChange" />
            </label>
            <label>
              <span>高度 (5-200)</span>
              <input type="number" v-model.number="form.mapHeight" min="5" max="200" @change="onMapSizeChange" />
            </label>
          </div>

          <div class="form-row">
            <label>
              <span>小车数量 (1-50)</span>
              <input type="number" v-model.number="form.carCount" min="1" max="50" @change="updateCarCount" />
            </label>
            <label>
              <span>障碍密度 (0-0.9)</span>
              <input type="number" step="0.05" v-model.number="form.obstacleDensity" min="0" max="0.9" @change="randomGenerate" />
            </label>
          </div>

          <label>
            <span>路径算法</span>
            <select v-model="form.algorithm">
              <option v-for="a in ALGORITHMS" :key="a.value" :value="a.value">{{ a.label }}</option>
            </select>
          </label>

          <!-- 小车位置列表 -->
          <div class="car-section">
            <h3>小车初始位置 <span class="hint">(点击格子放置选中的小车)</span></h3>
            <div class="car-list">
              <div
                v-for="(car, idx) in carPositions"
                :key="car.carId"
                :class="['car-item', { active: selectedCarIdx === idx }]"
                @click="selectedCarIdx = idx"
              >
                <span class="car-dot" :style="{
                  background: ['#4fc3f7','#ff8a65','#81c784','#ffd54f','#ba68c8','#ce93d8','#90caf9','#ffab91'][idx % 8]
                }"></span>
                <span class="car-id">{{ car.carId }}</span>
                <span class="car-pos">({{ car.x }}, {{ car.y }})</span>
              </div>
            </div>
          </div>

          <button class="btn primary large" :disabled="saving" @click="onSave">
            {{ saving ? '保存中...' : '保存地图' }}
          </button>
        </div>

        <!-- 右侧：障碍物编辑网格 -->
        <div class="editor-right">
          <div class="tool-section">
            <h3>地图编辑</h3>
            <div class="tool-row">
              <div class="tool-btns">
                <button :class="['tool-btn', { active: editTool === 'obstacle' }]" @click="editTool = 'obstacle'">
                  障碍物模式
                </button>
                <button :class="['tool-btn', { active: editTool === 'car' }]" @click="editTool = 'car'">
                  小车位置模式
                </button>
              </div>
              <button class="tool-btn random-btn" @click="randomGenerate">
                🎲 随机生成地图
              </button>
            </div>
          </div>

          <div class="grid-hint">
            障碍物: {{ obstacles.length }} 个 | 
            小车: {{ carPositions.length }} 辆 | 
            {{ editTool === 'obstacle' ? '点击格子添加/移除障碍物' : '选中上方小车后，点击格子放置' }}
          </div>

          <div class="grid-scroll">
            <div :style="gridStyle">
              <template v-for="y in range(form.mapHeight)" :key="'r'+y">
                <div
                  v-for="x in range(form.mapWidth)"
                  :key="'c'+x+','+y"
                  class="cell"
                  :style="{ background: cellColor(x-1, y-1) }"
                  @click="onCellClick(x-1, y-1)"
                ></div>
              </template>
            </div>
          </div>
        </div>
      </div>

      <!-- 已保存地图列表 -->
      <div v-if="activeTab === 'list'" class="map-list-view">
        <div v-if="savedMaps.length === 0" class="empty">
          <div class="empty-icon">🗺️</div>
          <div class="empty-text">暂无保存的地图</div>
          <div class="empty-hint">点击"地图编辑器"创建新地图</div>
        </div>
        <div v-for="map in savedMaps" :key="map.id" class="map-card">
          <div class="map-info">
            <div class="map-name">{{ map.name }}</div>
            <div class="map-meta">
              {{ map.mapWidth }}×{{ map.mapHeight }}
              · {{ map.carPositions?.length || map.carCount }} 辆小车
              · {{ map.obstacles?.length || 0 }} 障碍物
              · {{ ALGORITHMS.find(a => a.value === map.algorithm)?.label || map.algorithm || 'A*' }}
            </div>
            <div class="map-time" v-if="map.createdAt">
              创建时间: {{ formatTime(map.createdAt) }}
              <span v-if="map.createdBy"> · 创建者: {{ map.createdBy }}</span>
            </div>
          </div>
          <div class="map-actions">
            <button class="mini-btn edit" title="编辑" @click="loadMapToEditor(map)">✎</button>
            <button class="mini-btn delete" title="删除" @click="deleteMap(map)">✕</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.config-page {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: #161616;
}
.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 52px;
  padding: 0 20px;
  background: #222;
  border-bottom: 1px solid #333;
  flex-shrink: 0;
}
.brand {
  display: flex;
  align-items: center;
  gap: 12px;
}
.brand h1 { font-size: 16px; color: #fff; }
.role-badge {
  font-size: 11px;
  padding: 2px 10px;
  border-radius: 10px;
  font-weight: 600;
}
.role-badge.config { background: rgba(255, 213, 79, 0.2); color: #ffd54f; }
.user-area { display: flex; align-items: center; gap: 12px; }
.user-name { font-size: 13px; color: #aaa; }
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
.btn.primary { background: #4fc3f7; border-color: #4fc3f7; color: #10242e; font-weight: 600; }
.btn.primary:hover:not(:disabled) { background: #6fcef9; }
.btn.primary:disabled { opacity: 0.6; cursor: not-allowed; }
.btn.large { padding: 12px 20px; font-size: 14px; width: 100%; margin-top: 8px; }
.btn.logout { color: #ff8a65; border-color: #ff8a65; background: transparent; }

.content {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  padding: 20px;
}

.tab-bar {
  display: flex;
  gap: 4px;
  background: #262626;
  border-radius: 8px;
  padding: 4px;
  margin-bottom: 20px;
  width: fit-content;
}
.tab-bar button {
  border: none;
  background: transparent;
  color: #888;
  font-size: 13px;
  font-weight: 600;
  padding: 10px 24px;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s;
}
.tab-bar button.active {
  background: #3a3a3a;
  color: #e8e8e8;
}
.tab-bar button:hover:not(.active) { color: #bbb; }

/* 编辑器布局 */
.editor-area {
  display: flex;
  gap: 24px;
  flex: 1;
  min-height: 0;
}
.editor-left {
  width: 340px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.editor-left h2 { font-size: 18px; color: #fff; margin: 0; }
.editor-left label {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.editor-left label span { font-size: 12px; color: #999; }
.editor-left input, .editor-left select {
  background: #2a2a2a;
  border: 1px solid #444;
  border-radius: 6px;
  color: #eee;
  padding: 8px 10px;
  font-size: 13px;
}
.editor-left input:focus, .editor-left select:focus {
  outline: none;
  border-color: #4fc3f7;
}
.form-row { display: flex; gap: 10px; }
.form-row label { flex: 1; }

.msg {
  padding: 10px 14px;
  border-radius: 6px;
  font-size: 13px;
  background: rgba(129, 199, 132, 0.15);
  border: 1px solid #81c784;
  color: #a5d6a7;
}
.msg.error {
  background: rgba(229, 115, 115, 0.15);
  border-color: #e57373;
  color: #ef9a9a;
}

/* 小车列表 */
.car-section h3 { font-size: 13px; color: #aaa; margin-bottom: 8px; }
.car-section .hint { font-size: 11px; color: #666; font-weight: normal; }
.car-list { display: flex; flex-direction: column; gap: 3px; max-height: 160px; overflow-y: auto; }
.car-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-radius: 4px;
  background: #2a2a2a;
  border: 1px solid transparent;
  cursor: pointer;
  font-size: 12px;
}
.car-item:hover { background: #333; }
.car-item.active { border-color: #4fc3f7; background: rgba(79,195,247,0.1); }
.car-dot { width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0; }
.car-id { color: #ddd; font-weight: 600; }
.car-pos { color: #888; font-size: 11px; margin-left: auto; }

/* 编辑器右侧 */
.editor-right {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.tool-section {
  margin-bottom: 10px;
}
.tool-section h3 {
  font-size: 14px;
  color: #ff9800;
  margin-bottom: 8px;
}
.tool-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
}
.tool-btns { display: flex; gap: 8px; }
.tool-btn {
  padding: 6px 16px;
  background: #2a2a2a;
  border: 1px solid #444;
  border-radius: 6px;
  color: #ccc;
  font-size: 12px;
  cursor: pointer;
}
.tool-btn:hover { background: #333; }
.tool-btn.active {
  border-color: #ff9800;
  background: rgba(255,152,0,0.12);
  color: #ff9800;
}
.tool-btn.random-btn {
  border-color: #81c784;
  color: #81c784;
  background: rgba(129,199,132,0.1);
  font-weight: 600;
}
.tool-btn.random-btn:hover {
  background: rgba(129,199,132,0.2);
  border-color: #a5d6a7;
}
.grid-hint { font-size: 12px; color: #888; margin-bottom: 10px; }
.grid-scroll {
  flex: 1;
  overflow: auto;
  border: 1px solid #333;
  border-radius: 6px;
  background: #161616;
}
.cell {
  cursor: pointer;
  transition: background 0.1s;
}
.cell:hover {
  outline: 1px solid rgba(255,255,255,0.3);
  outline-offset: -1px;
}

/* 地图列表 */
.map-list-view {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.map-card {
  background: #1e1e1e;
  border: 1px solid #333;
  border-radius: 8px;
  padding: 16px;
  display: flex;
  align-items: center;
  gap: 14px;
  transition: background 0.15s;
}
.map-card:hover { background: #262626; }
.map-info { flex: 1; min-width: 0; }
.map-name { font-size: 15px; font-weight: 600; color: #e8e8e8; }
.map-meta { font-size: 12px; color: #888; margin-top: 4px; }
.map-time { font-size: 11px; color: #666; margin-top: 3px; }
.map-actions { display: flex; gap: 6px; flex-shrink: 0; }
.mini-btn {
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 4px;
  font-size: 14px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}
.mini-btn.edit { background: #ffd54f; color: #333; }
.mini-btn.edit:hover { filter: brightness(1.15); }
.mini-btn.delete { background: #e57373; color: #fff; }
.mini-btn.delete:hover { filter: brightness(1.15); }

.empty {
  text-align: center;
  padding: 60px 20px;
}
.empty-icon { font-size: 48px; margin-bottom: 16px; }
.empty-text { font-size: 16px; color: #aaa; }
.empty-hint { font-size: 13px; color: #666; margin-top: 8px; }
</style>
