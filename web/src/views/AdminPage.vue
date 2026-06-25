<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../store/authStore.js'
import { useSimulationStore } from '../store/simulationStore.js'
import { api } from '../utils/api.js'
import ServiceStatusBar from '../components/ServiceStatusBar.vue'

const router = useRouter()
const authStore = useAuthStore()
const simStore = useSimulationStore()

const activeTab = ref('users')  // 'users' | 'registrations' | 'services'
const services = computed(() => simStore.services)
const users = ref([])
const registrations = ref([])
const loading = ref(false)
const error = ref('')

// 新增用户弹窗
const createDialogVisible = ref(false)
const createUsername = ref('')
const createPassword = ref('')
const createRole = ref('USER')
const createError = ref('')

// 重置密码弹窗
const resetDialogVisible = ref(false)
const resetUserId = ref('')
const resetUsername = ref('')
const newPassword = ref('')
const resetError = ref('')

// 审批弹窗
const approveDialogVisible = ref(false)
const approveReqId = ref('')
const approveUsername = ref('')
const approveRequestedRole = ref('')
const approveAssignedRole = ref('')

onMounted(() => {
  loadData()
  loadServices()
})

async function loadServices() {
  try {
    const res = await api.getServiceStatus()
    simStore.setServices(res.services || {})
  } catch (e) { /* */ }
}

async function loadData() {
  loading.value = true
  error.value = ''
  try {
    const [userRes, regRes] = await Promise.all([
      api.listUsers(),
      api.listRegistrations()
    ])
    users.value = userRes.users || []
    registrations.value = regRes.registrations || []
  } catch (e) {
    error.value = e.message || '加载数据失败'
  } finally {
    loading.value = false
  }
}

// ===== 用户管理 =====

function openCreateUser() {
  createUsername.value = ''
  createPassword.value = ''
  createRole.value = 'USER'
  createError.value = ''
  createDialogVisible.value = true
}

async function confirmCreateUser() {
  createError.value = ''
  if (!createUsername.value.trim()) {
    createError.value = '用户名不能为空'
    return
  }
  if (!createPassword.value || createPassword.value.length < 6) {
    createError.value = '密码长度不能少于 6 个字符'
    return
  }
  try {
    await api.createUser(createUsername.value.trim(), createPassword.value, createRole.value)
    createDialogVisible.value = false
    await loadData()
  } catch (e) {
    createError.value = e.message || '创建失败'
  }
}

function openResetPassword(user) {
  resetUserId.value = user.id
  resetUsername.value = user.username
  newPassword.value = ''
  resetError.value = ''
  resetDialogVisible.value = true
}

async function confirmResetPassword() {
  resetError.value = ''
  if (!newPassword.value || newPassword.value.length < 6) {
    resetError.value = '密码长度不能少于 6 个字符'
    return
  }
  try {
    await api.resetPassword(resetUserId.value, newPassword.value)
    resetDialogVisible.value = false
    await loadData()
  } catch (e) {
    resetError.value = e.message || '重置失败'
  }
}

async function handleDeleteUser(user) {
  if (user.username === authStore.username) {
    alert('不能删除自己的账户')
    return
  }
  if (!confirm(`确定要删除用户 "${user.username}" 吗？此操作不可撤销。`)) return
  try {
    await api.deleteUser(user.id)
    await loadData()
  } catch (e) {
    alert(e.message || '删除失败')
  }
}

// ===== 注册审批 =====

function openApprove(req) {
  approveReqId.value = req.id
  approveUsername.value = req.username
  approveRequestedRole.value = req.requestedRole
  approveAssignedRole.value = req.requestedRole
  approveDialogVisible.value = true
}

async function confirmApprove() {
  try {
    await api.approveRegistration(approveReqId.value, approveAssignedRole.value)
    approveDialogVisible.value = false
    await loadData()
  } catch (e) {
    alert(e.message || '审批失败')
  }
}

async function handleReject(req) {
  if (!confirm(`确定要拒绝 "${req.username}" 的注册请求吗？`)) return
  try {
    await api.rejectRegistration(req.id)
    await loadData()
  } catch (e) {
    alert(e.message || '拒绝失败')
  }
}

function logout() {
  authStore.logout()
  router.push('/login')
}

function roleLabel(role) {
  const map = { ADMIN: '管理员', CONFIGURATOR: '配置员', USER: '用户' }
  return map[role] || role
}

function statusLabel(status) {
  const map = { PENDING: '待审批', APPROVED: '已通过', REJECTED: '已拒绝' }
  return map[status] || status
}

function formatTime(ts) {
  if (!ts) return '-'
  return new Date(ts).toLocaleString('zh-CN')
}
</script>

<template>
  <div class="admin-page">
    <!-- 顶栏 -->
    <header class="topbar">
      <div class="brand">
        <h1>变电站巡检仿真系统</h1>
        <span class="role-badge admin">管理员</span>
      </div>
      <div class="user-area">
        <ServiceStatusBar />
        <span class="user-name">{{ authStore.username }}</span>
        <button class="btn logout" @click="logout">退出登录</button>
      </div>
    </header>

    <!-- Tab 导航 -->
    <div class="tab-bar">
      <button :class="{ active: activeTab === 'users' }" @click="activeTab = 'users'">
        用户管理 ({{ users.length }})
      </button>
      <button :class="{ active: activeTab === 'registrations' }" @click="activeTab = 'registrations'">
        注册审批 ({{ registrations.filter(r => r.status === 'PENDING').length }} 待处理)
      </button>
      <button :class="{ active: activeTab === 'services' }" @click="activeTab = 'services'">
        服务状态
      </button>
    </div>

    <!-- 加载/错误状态 -->
    <div v-if="loading" class="loading">加载中...</div>
    <div v-else-if="error" class="error-msg">{{ error }}</div>

    <!-- 用户列表 -->
    <div v-else-if="activeTab === 'users'" class="content">
      <div class="section-header">
        <span></span>
        <button class="btn primary" @click="openCreateUser">+ 新增用户</button>
      </div>
      <table class="data-table">
        <thead>
          <tr>
            <th>用户名</th>
            <th>角色</th>
            <th>状态</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="u in users" :key="u.id">
            <td>{{ u.username }}</td>
            <td><span class="badge" :class="u.role.toLowerCase()">{{ roleLabel(u.role) }}</span></td>
            <td><span :class="u.approved ? 'text-green' : 'text-yellow'">{{ u.approved ? '已激活' : '未审批' }}</span></td>
            <td>{{ formatTime(u.createdAt) }}</td>
            <td class="actions">
              <button class="btn sm" @click="openResetPassword(u)">重置密码</button>
              <button class="btn sm danger" @click="handleDeleteUser(u)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="users.length === 0" class="empty">暂无用户</div>
    </div>

    <!-- 注册请求列表 -->
    <div v-else-if="activeTab === 'registrations'" class="content">
      <table class="data-table">
        <thead>
          <tr>
            <th>用户名</th>
            <th>申请角色</th>
            <th>状态</th>
            <th>申请时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in registrations" :key="r.id">
            <td>{{ r.username }}</td>
            <td>{{ roleLabel(r.requestedRole) }}</td>
            <td>
              <span :class="{
                'text-yellow': r.status === 'PENDING',
                'text-green': r.status === 'APPROVED',
                'text-red': r.status === 'REJECTED'
              }">{{ statusLabel(r.status) }}</span>
            </td>
            <td>{{ formatTime(r.createdAt) }}</td>
            <td class="actions">
              <template v-if="r.status === 'PENDING'">
                <button class="btn sm primary" @click="openApprove(r)">通过</button>
                <button class="btn sm danger" @click="handleReject(r)">拒绝</button>
              </template>
              <span v-else class="text-muted">已处理</span>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="registrations.length === 0" class="empty">暂无注册请求</div>
    </div>

    <!-- 服务状态 -->
    <div v-else-if="activeTab === 'services'" class="content">
      <table class="data-table">
        <thead>
          <tr><th>组件</th><th>实例数</th><th>实例详情</th></tr>
        </thead>
        <tbody>
          <tr v-for="(list, type) in services" :key="type">
            <td>{{ type }}</td>
            <td><span :class="list.length > 0 ? 'text-green' : 'text-red'">{{ list.length }}</span></td>
            <td class="mono">
              <div v-for="inst in list" :key="inst.instanceId" class="instance-row">
                {{ inst.host }} &nbsp; pid {{ inst.pid }} &nbsp; {{ formatTime(inst.startedAt) }}
              </div>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="Object.keys(services).length === 0" class="empty">暂无运行中的后端服务</div>
    </div>

    <!-- 新增用户弹窗 -->
    <div v-if="createDialogVisible" class="overlay" @click.self="createDialogVisible = false">
      <div class="dialog">
        <h3>新增用户</h3>
        <label>
          <span>用户名</span>
          <input v-model="createUsername" type="text" placeholder="3-32个字符" />
        </label>
        <label>
          <span>密码</span>
          <input v-model="createPassword" type="password" placeholder="至少6个字符" />
        </label>
        <label>
          <span>角色</span>
          <select v-model="createRole">
            <option value="USER">用户</option>
            <option value="CONFIGURATOR">配置员</option>
            <option value="ADMIN">管理员</option>
          </select>
        </label>
        <div v-if="createError" class="error-msg-inline">{{ createError }}</div>
        <div class="dialog-actions">
          <button class="btn" @click="createDialogVisible = false">取消</button>
          <button class="btn primary" @click="confirmCreateUser">确认创建</button>
        </div>
      </div>
    </div>

    <!-- 重置密码弹窗 -->
    <div v-if="resetDialogVisible" class="overlay" @click.self="resetDialogVisible = false">
      <div class="dialog">
        <h3>重置密码</h3>
        <p class="dialog-hint">用户：{{ resetUsername }}</p>
        <label>
          <span>新密码</span>
          <input v-model="newPassword" type="password" placeholder="至少6个字符" />
        </label>
        <div v-if="resetError" class="error-msg-inline">{{ resetError }}</div>
        <div class="dialog-actions">
          <button class="btn" @click="resetDialogVisible = false">取消</button>
          <button class="btn primary" @click="confirmResetPassword">确认重置</button>
        </div>
      </div>
    </div>

    <!-- 审批弹窗 -->
    <div v-if="approveDialogVisible" class="overlay" @click.self="approveDialogVisible = false">
      <div class="dialog">
        <h3>审批注册请求</h3>
        <p class="dialog-hint">用户：{{ approveUsername }}，申请角色：{{ roleLabel(approveRequestedRole) }}</p>
        <label>
          <span>分配权限</span>
          <select v-model="approveAssignedRole">
            <option value="USER">用户</option>
            <option value="CONFIGURATOR">配置员</option>
            <option value="ADMIN">管理员</option>
          </select>
        </label>
        <div class="dialog-actions">
          <button class="btn" @click="approveDialogVisible = false">取消</button>
          <button class="btn primary" @click="confirmApprove">确认通过</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.admin-page {
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
.brand h1 {
  font-size: 16px;
  color: #fff;
}
.role-badge {
  font-size: 11px;
  padding: 2px 10px;
  border-radius: 10px;
  font-weight: 600;
}
.role-badge.admin { background: rgba(229, 115, 115, 0.2); color: #ef9a9a; }
.user-area {
  display: flex;
  align-items: center;
  gap: 12px;
}
.user-name {
  font-size: 13px;
  color: #aaa;
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
.btn.primary { background: #4fc3f7; border-color: #4fc3f7; color: #10242e; font-weight: 600; }
.btn.primary:hover { background: #6fcef9; }
.btn.danger { color: #e57373; }
.btn.sm { padding: 4px 10px; font-size: 12px; }
.btn.logout { color: #ff8a65; border-color: #ff8a65; background: transparent; }
.tab-bar {
  display: flex;
  background: #1e1e1e;
  border-bottom: 1px solid #333;
  flex-shrink: 0;
}
.tab-bar button {
  flex: 1;
  border: none;
  background: transparent;
  color: #888;
  padding: 12px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: all 0.2s;
}
.tab-bar button.active {
  color: #4fc3f7;
  border-bottom-color: #4fc3f7;
}
.tab-bar button:hover:not(.active) { color: #bbb; }
.content {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}
.section-header {
  display: flex; justify-content: space-between; align-items: center;
  margin-bottom: 12px;
}
.loading, .empty {
  text-align: center;
  color: #888;
  padding: 60px 0;
  font-size: 14px;
}
.error-msg {
  background: rgba(229, 115, 115, 0.1);
  border: 1px solid #e57373;
  color: #ef9a9a;
  padding: 12px 16px;
  margin: 16px 20px;
  border-radius: 6px;
  font-size: 13px;
}
.data-table {
  width: 100%;
  border-collapse: collapse;
}
.data-table th {
  text-align: left;
  padding: 10px 12px;
  font-size: 12px;
  color: #888;
  text-transform: uppercase;
  border-bottom: 1px solid #333;
}
.data-table td {
  padding: 12px;
  font-size: 13px;
  border-bottom: 1px solid #2a2a2a;
  color: #ddd;
}
.data-table tr:hover td { background: rgba(255,255,255,0.02); }
.actions { display: flex; gap: 8px; }
.badge {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 4px;
  font-weight: 600;
}
.badge.admin { background: rgba(229,115,115,0.2); color: #ef9a9a; }
.badge.configurator { background: rgba(255,213,79,0.2); color: #ffd54f; }
.badge.user { background: rgba(79,195,247,0.2); color: #4fc3f7; }
.text-green { color: #81c784; }
.text-yellow { color: #ffd54f; }
.text-red { color: #e57373; }
.text-muted { color: #666; font-size: 12px; }

/* 弹窗 */
.overlay {
  position: fixed; inset: 0;
  background: rgba(0,0,0,0.6);
  display: flex; align-items: center; justify-content: center;
  z-index: 100;
}
.dialog {
  width: 360px;
  background: #262626;
  border: 1px solid #383838;
  border-radius: 10px;
  padding: 24px;
}
.dialog h3 { font-size: 15px; color: #fff; margin-bottom: 8px; }
.dialog-hint { font-size: 13px; color: #888; margin-bottom: 16px; }
.dialog label {
  display: flex; flex-direction: column; gap: 6px;
  margin-bottom: 12px;
}
.dialog label span { font-size: 13px; color: #aaa; }
.dialog input, .dialog select {
  background: #1d1d1d;
  border: 1px solid #444;
  border-radius: 6px;
  color: #eee;
  padding: 8px 10px;
  font-size: 13px;
}
.dialog input:focus, .dialog select:focus {
  outline: none; border-color: #4fc3f7;
}
.dialog select option { background: #2a2a2a; color: #eee; }
.error-msg-inline {
  color: #e57373; font-size: 12px; margin-bottom: 12px;
}
.dialog-actions {
  display: flex; gap: 10px; justify-content: flex-end;
  margin-top: 16px;
}
.mono { font-family: 'Consolas', 'Courier New', monospace; font-size: 12px; }
.instance-row { margin-bottom: 2px; color: #aaa; }
</style>
