<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../store/authStore.js'
import { api } from '../utils/api.js'

const router = useRouter()
const authStore = useAuthStore()

const username = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)

// 角色 → 主页路由
const roleHome = {
  ADMIN: '/admin',
  CONFIGURATOR: '/configurator',
  USER: '/user'
}

async function onLogin() {
  error.value = ''
  if (!username.value || !password.value) {
    error.value = '请输入用户名和密码'
    return
  }

  loading.value = true
  try {
    const res = await api.login(username.value, password.value)
    authStore.setAuth(res.token, res.username, res.role, res.userId)
    router.push(roleHome[res.role] || '/user')
  } catch (e) {
    error.value = e.message || '登录失败'
  } finally {
    loading.value = false
  }
}

function goRegister() {
  router.push('/register')
}
</script>

<template>
  <div class="auth-page">
    <div class="auth-card">
      <div class="auth-header">
        <h1>变电站巡检仿真系统</h1>
        <p>用户登录</p>
      </div>

      <div v-if="error" class="error-msg">{{ error }}</div>

      <form @submit.prevent="onLogin" class="auth-form">
        <label>
          <span>用户名</span>
          <input v-model="username" type="text" placeholder="请输入用户名" autocomplete="username" />
        </label>
        <label>
          <span>密码</span>
          <input v-model="password" type="password" placeholder="请输入密码" autocomplete="current-password" />
        </label>
        <button type="submit" class="btn primary" :disabled="loading">
          {{ loading ? '登录中...' : '登 录' }}
        </button>
      </form>

      <div class="auth-footer">
        <span>还没有账号？</span>
        <a @click="goRegister">立即注册</a>
      </div>
    </div>

    <div class="auth-hint">
      <p>默认管理员：admin / admin123</p>
    </div>
  </div>
</template>

<style scoped>
.auth-page {
  height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: #161616;
}
.auth-card {
  width: 380px;
  background: #1e1e1e;
  border: 1px solid #333;
  border-radius: 12px;
  padding: 36px 32px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.4);
}
.auth-header {
  text-align: center;
  margin-bottom: 24px;
}
.auth-header h1 {
  font-size: 20px;
  color: #fff;
  margin-bottom: 6px;
}
.auth-header p {
  font-size: 14px;
  color: #888;
}
.error-msg {
  background: rgba(229, 115, 115, 0.15);
  border: 1px solid #e57373;
  color: #ef9a9a;
  padding: 10px 14px;
  border-radius: 6px;
  font-size: 13px;
  margin-bottom: 16px;
}
.auth-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.auth-form label {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.auth-form label span {
  font-size: 13px;
  color: #aaa;
}
.auth-form input {
  background: #2a2a2a;
  border: 1px solid #444;
  border-radius: 6px;
  color: #eee;
  padding: 10px 12px;
  font-size: 14px;
}
.auth-form input:focus {
  outline: none;
  border-color: #4fc3f7;
}
.btn {
  border: none;
  border-radius: 6px;
  padding: 12px;
  font-size: 14px;
  font-weight: 600;
  transition: all 0.2s;
}
.btn.primary {
  background: #4fc3f7;
  color: #10242e;
  margin-top: 4px;
}
.btn.primary:hover:not(:disabled) {
  background: #6fcef9;
}
.btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.auth-footer {
  text-align: center;
  margin-top: 20px;
  font-size: 13px;
  color: #888;
}
.auth-footer a {
  color: #4fc3f7;
  cursor: pointer;
  margin-left: 4px;
}
.auth-footer a:hover {
  text-decoration: underline;
}
.auth-hint {
  margin-top: 20px;
  font-size: 12px;
  color: #666;
}
</style>
