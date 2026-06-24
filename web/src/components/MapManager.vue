<script setup>
import { ref, computed, onMounted } from 'vue'
import { useSimulationStore } from '../store/simulationStore.js'

const store = useSimulationStore()

const STORAGE_KEY = 'blackbox_saved_maps'

// ===== 状态 =====
const savedMaps = ref([])        // 已保存的地图列表
const saveDialogVisible = ref(false)
const editDialogVisible = ref(false)
const saveName = ref('')
const editName = ref('')
const editingMapId = ref(null)

// ===== 从 localStorage 加载已保存的地图 =====
function loadSavedMaps() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    savedMaps.value = raw ? JSON.parse(raw) : []
  } catch {
    savedMaps.value = []
  }
}

function persistMaps() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(savedMaps.value))
}

onMounted(loadSavedMaps)

// ===== 获取当前地图数据 =====
function captureCurrentMap() {
  const w = store.config.mapWidth
  const h = store.config.mapHeight
  const obstacles = []
  if (Array.isArray(store.mapBlock)) {
    for (let y = 0; y < h && y < store.mapBlock.length; y++) {
      const row = store.mapBlock[y]
      if (!row) continue
      for (let x = 0; x < w && x < row.length; x++) {
        if (row[x] === 1) obstacles.push({ x, y })
      }
    }
  }
  const carPositions = (store.cars || []).map(c => ({
    carId: c.carId,
    x: c.x,
    y: c.y
  }))
  return {
    mapWidth: w,
    mapHeight: h,
    carCount: carPositions.length || store.config.carCount,
    obstacleDensity: store.config.obstacleDensity,
    algorithm: store.config.algorithm || 'A_STAR',
    obstacles,
    carPositions
  }
}

// ===== 是否有当前地图可保存 =====
const hasMap = computed(() => {
  return Array.isArray(store.mapBlock) && store.mapBlock.length > 0
})

// ===== 保存地图 =====
function openSaveDialog() {
  if (!hasMap.value) {
    alert('当前没有地图数据，请先启动仿真或编辑地图')
    return
  }
  saveName.value = `地图_${new Date().toLocaleDateString().replace(/\//g, '-')}_${Date.now() % 100000}`
  saveDialogVisible.value = true
}

function confirmSave() {
  const name = saveName.value.trim()
  if (!name) { alert('请输入地图名称'); return }
  if (savedMaps.value.some(m => m.name === name)) {
    if (!confirm(`名称"${name}"已存在，是否覆盖？`)) return
    savedMaps.value = savedMaps.value.filter(m => m.name !== name)
  }
  const mapData = captureCurrentMap()
  savedMaps.value.push({
    id: Date.now().toString(36),
    name,
    createdAt: Date.now(),
    ...mapData
  })
  persistMaps()
  saveDialogVisible.value = false
  saveName.value = ''
}

function cancelSave() {
  saveDialogVisible.value = false
  saveName.value = ''
}

// ===== 加载地图（通过 SET_MAP_EDIT 发送到后端） =====
function loadMap(map) {
  if (!confirm(`确定要加载地图"${map.name}"吗？当前仿真将被替换。`)) return

  // 构建 SET_MAP_EDIT 数据格式
  const editConfig = {
    mapWidth: map.mapWidth,
    mapHeight: map.mapHeight,
    carCount: map.carCount,
    obstacleDensity: map.obstacleDensity || 0,
    algorithm: map.algorithm || 'A_STAR',
    customObstacles: map.obstacles || [],
    carPositions: map.carPositions || []
  }

  // 通过 store 的 enterEditMode 设置编辑状态，然后发送命令
  store.enterEditMode({
    mapWidth: map.mapWidth,
    mapHeight: map.mapHeight,
    carCount: map.carCount
  })

  // 用保存的障碍物和小车位置覆盖编辑数据
  store.editObstacles = (map.obstacles || []).map(o => ({ x: o.x, y: o.y }))
  store.editCarPositions = (map.carPositions || []).map(c => ({
    carId: c.carId,
    x: c.x,
    y: c.y
  }))
  store.editCarCount = store.editCarPositions.length

  // 触发父组件发送 SET_MAP_EDIT 命令
  // 我们通过 window 上的自定义事件来通知 App.vue
  window.dispatchEvent(new CustomEvent('load-saved-map', { detail: editConfig }))
}

// ===== 删除地图 =====
function deleteMap(map) {
  if (!confirm(`确定要删除地图"${map.name}"吗？`)) return
  savedMaps.value = savedMaps.value.filter(m => m.id !== map.id)
  persistMaps()
}

// ===== 重命名地图 =====
function openRename(map) {
  editingMapId.value = map.id
  editName.value = map.name
  editDialogVisible.value = true
}

function confirmRename() {
  const name = editName.value.trim()
  if (!name) { alert('请输入地图名称'); return }
  const map = savedMaps.value.find(m => m.id === editingMapId.value)
  if (map) {
    if (savedMaps.value.some(m => m.name === name && m.id !== editingMapId.value)) {
      alert('名称已存在')
      return
    }
    map.name = name
    persistMaps()
  }
  editDialogVisible.value = false
  editingMapId.value = null
  editName.value = ''
}

function cancelRename() {
  editDialogVisible.value = false
  editingMapId.value = null
  editName.value = ''
}

// ===== 导出地图为 JSON 文件 =====
function exportMap(map) {
  const blob = new Blob([JSON.stringify(map, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${map.name}.json`
  a.click()
  URL.revokeObjectURL(url)
}

// ===== 导入地图 JSON 文件 =====
function importMap() {
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = '.json'
  input.onchange = (e) => {
    const file = e.target.files?.[0]
    if (!file) return
    const reader = new FileReader()
    reader.onload = (ev) => {
      try {
        const map = JSON.parse(ev.target.result)
        if (!map.mapWidth || !map.mapHeight) {
          alert('无效的地图文件：缺少 mapWidth 或 mapHeight')
          return
        }
        const name = map.name || `导入_${Date.now() % 100000}`
        if (savedMaps.value.some(m => m.name === name)) {
          if (!confirm(`名称"${name}"已存在，是否覆盖？`)) return
          savedMaps.value = savedMaps.value.filter(m => m.name !== name)
        }
        savedMaps.value.push({
          id: Date.now().toString(36),
          name,
          createdAt: Date.now(),
          mapWidth: map.mapWidth,
          mapHeight: map.mapHeight,
          carCount: map.carCount || 0,
          obstacleDensity: map.obstacleDensity || 0,
          algorithm: map.algorithm || 'A_STAR',
          obstacles: map.obstacles || [],
          carPositions: map.carPositions || []
        })
        persistMaps()
        alert(`地图"${name}"导入成功`)
      } catch {
        alert('无效的 JSON 文件')
      }
    }
    reader.readAsText(file)
  }
  input.click()
}

// ===== 格式化时间 =====
function formatTime(ts) {
  const d = new Date(ts)
  return d.toLocaleString('zh-CN')
}
</script>

<template>
  <div class="map-manager">
    <!-- 操作按钮区 -->
    <div class="actions-row">
      <button class="action-btn save" :disabled="!hasMap" @click="openSaveDialog">
        💾 保存当前地图
      </button>
    </div>
    <div class="actions-row">
      <button class="action-btn import" @click="importMap">
        📥 导入地图
      </button>
    </div>

    <!-- 地图列表 -->
    <div class="map-list" v-if="savedMaps.length > 0">
      <div class="list-header">
        <span>已保存的地图 ({{ savedMaps.length }})</span>
      </div>
      <div
        v-for="map in savedMaps"
        :key="map.id"
        class="map-card"
      >
        <div class="map-info">
          <div class="map-name">{{ map.name }}</div>
          <div class="map-meta">
            {{ map.mapWidth }}×{{ map.mapHeight }}
            · {{ map.carPositions?.length || map.carCount }} 辆小车
            · {{ Math.round((map.obstacleDensity || 0) * 100) }}% 障碍
          </div>
          <div class="map-time">{{ formatTime(map.createdAt) }}</div>
        </div>
        <div class="map-actions">
          <button class="mini-btn load" title="加载并运行" @click="loadMap(map)">▶</button>
          <button class="mini-btn rename" title="重命名" @click="openRename(map)">✎</button>
          <button class="mini-btn export" title="导出" @click="exportMap(map)">📤</button>
          <button class="mini-btn delete" title="删除" @click="deleteMap(map)">✕</button>
        </div>
      </div>
    </div>

    <!-- 空状态 -->
    <div class="empty" v-else>
      <div class="empty-icon">🗺️</div>
      <div class="empty-text">暂无保存的地图</div>
      <div class="empty-hint">启动仿真后点击"保存当前地图"<br>或点击"导入地图"加载 JSON 文件</div>
    </div>

    <!-- 保存对话框 -->
    <div v-if="saveDialogVisible" class="dialog-overlay" @click.self="cancelSave">
      <div class="dialog">
        <h3>保存地图</h3>
        <input
          v-model="saveName"
          class="dialog-input"
          placeholder="输入地图名称"
          @keyup.enter="confirmSave"
          ref="saveInput"
        />
        <div class="dialog-actions">
          <button class="dialog-btn cancel" @click="cancelSave">取消</button>
          <button class="dialog-btn confirm" @click="confirmSave">保存</button>
        </div>
      </div>
    </div>

    <!-- 重命名对话框 -->
    <div v-if="editDialogVisible" class="dialog-overlay" @click.self="cancelRename">
      <div class="dialog">
        <h3>重命名地图</h3>
        <input
          v-model="editName"
          class="dialog-input"
          placeholder="输入新名称"
          @keyup.enter="confirmRename"
        />
        <div class="dialog-actions">
          <button class="dialog-btn cancel" @click="cancelRename">取消</button>
          <button class="dialog-btn confirm" @click="confirmRename">确定</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.map-manager {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* 操作按钮 */
.actions-row {
  display: flex;
  gap: 8px;
}

.action-btn {
  flex: 1;
  border: none;
  border-radius: 6px;
  padding: 9px 0;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: filter 0.15s, opacity 0.15s;
}

.action-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.action-btn:not(:disabled):hover {
  filter: brightness(1.15);
}

.action-btn.save {
  background: #4fc3f7;
  color: #10242e;
}

.action-btn.import {
  background: #81c784;
  color: #10242e;
}

/* 地图列表 */
.map-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.list-header {
  font-size: 12px;
  color: #888;
  font-weight: 600;
  padding: 4px 0;
}

.map-card {
  background: #262626;
  border-radius: 8px;
  padding: 12px;
  display: flex;
  align-items: center;
  gap: 10px;
  transition: background 0.15s;
}

.map-card:hover {
  background: #2e2e2e;
}

.map-info {
  flex: 1;
  min-width: 0;
}

.map-name {
  font-size: 14px;
  font-weight: 600;
  color: #e8e8e8;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.map-meta {
  font-size: 11px;
  color: #888;
  margin-top: 3px;
}

.map-time {
  font-size: 10px;
  color: #666;
  margin-top: 2px;
}

.map-actions {
  display: flex;
  gap: 4px;
  flex-shrink: 0;
}

.mini-btn {
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 4px;
  font-size: 13px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: filter 0.15s;
}

.mini-btn:hover {
  filter: brightness(1.2);
}

.mini-btn.load {
  background: #4fc3f7;
  color: #10242e;
}

.mini-btn.rename {
  background: #ffd54f;
  color: #333;
}

.mini-btn.export {
  background: #81c784;
  color: #10242e;
}

.mini-btn.delete {
  background: #e57373;
  color: #fff;
}

/* 空状态 */
.empty {
  text-align: center;
  padding: 32px 16px;
}

.empty-icon {
  font-size: 40px;
  margin-bottom: 12px;
}

.empty-text {
  font-size: 14px;
  color: #aaa;
  margin-bottom: 8px;
}

.empty-hint {
  font-size: 12px;
  color: #666;
  line-height: 1.6;
}

/* 对话框 */
.dialog-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 200;
}

.dialog {
  background: #2a2a2a;
  border-radius: 12px;
  padding: 24px;
  width: 320px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.5);
}

.dialog h3 {
  font-size: 15px;
  color: #e8e8e8;
  margin-bottom: 16px;
}

.dialog-input {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid #444;
  border-radius: 6px;
  background: #1e1e1e;
  color: #e8e8e8;
  font-size: 14px;
  outline: none;
  box-sizing: border-box;
}

.dialog-input:focus {
  border-color: #4fc3f7;
}

.dialog-actions {
  display: flex;
  gap: 8px;
  margin-top: 16px;
  justify-content: flex-end;
}

.dialog-btn {
  padding: 8px 20px;
  border: none;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: filter 0.15s;
}

.dialog-btn:hover {
  filter: brightness(1.15);
}

.dialog-btn.cancel {
  background: #444;
  color: #ccc;
}

.dialog-btn.confirm {
  background: #4fc3f7;
  color: #10242e;
}
</style>
