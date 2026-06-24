<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../utils/api.js'

const router = useRouter()

const username = ref('')
const password = ref('')
const confirmPassword = ref('')
const role = ref('USER')
const error = ref('')
const success = ref('')
const loading = ref(false)

const roles = [
  { value: 'USER', label: '用户 - 执行实验操作' },
  { value: 'CONFIGURATOR', label: '配置员 - 配置地图' },
  { value: 'ADMIN', label: '管理员 - 用户管理' }
]

async function onRegister() {
  error.value = ''
  success.value = ''

  if (!username.value || !password.value || !confirmPassword.value) {
    error.value = '请填写所有字段'
    return
  }
  if (password.value !== confirmPassword.value) {
    error.value = '两次输入的密码不一致'
    return
  }
  if (password.value.length < 6) {
    error.value = '密码长度不能少于 6 个字符'
    return
  }

  loading.value = true
  try {
    const res = await api.register(username.value, password.value, confirmPassword.value, role.value)
    success.value = res.message || '注册请求已提交'
    // 3秒后跳转到登录页
    setTimeout(() => router.push('/login'), 2000)
  } catch (e) {
    error.value = e.message || '注册失败'
  } finally {
    loading.value = false
  }
}

function goLogin() {
  router.push('/login')
}
</script>

<template>
  <div class="auth-page">
    <div class="auth-card">
      <div class="auth-header">
        <h1>变电站巡检仿真系统</h1>
        <p>用户注册</p>
      </div>

      <div v-if="error" class="error-msg">{{ error }}</div>
      <div v-if="success" class="success-msg">{{ success }}，即将跳转到登录页...</div>

      <form v-if="!success" @submit.prevent="onRegister" class="auth-form">
        <label>
          <span>用户名</span>
          <input v-model="username" type="text" placeholder="3-32位，支持中英文、数字、下划线" autocomplete="off" />
        </label>
        <label>
          <span>密码</span>
          <input v-model="password" type="password" placeholder="至少6个字符" autocomplete="new-password" />
        </label>
        <label>
          <span>确认密码</span>
          <input v-model="confirmPassword" type="password" placeholder="再次输入密码" autocomplete="new-password" />
        </label>
        <label>
          <span>用户权限</span>
          <select v-model="role">
            <option v-for="r in roles" :key="r.value" :value="r.value">{{ r.label }}</option>
          </select>
        </label>
        <button type="submit" class="btn primary" :disabled="loading">
          {{ loading ? '提交中...' : '注 册' }}
        </button>
      </form>

      <div class="auth-footer">
        <span>已有账号？</span>
        <a @click="goLogin">立即登录</a>
      </div>
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
  width: 400px;
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
.success-msg {
  background: rgba(129, 199, 132, 0.15);
  border: 1px solid #81c784;
  color: #a5d6a7;
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
.auth-form input,
.auth-form select {
  background: #2a2a2a;
  border: 1px solid #444;
  border-radius: 6px;
  color: #eee;
  padding: 10px 12px;
  font-size: 14px;
}
.auth-form input:focus,
.auth-form select:focus {
  outline: none;
  border-color: #4fc3f7;
}
.auth-form select option {
  background: #2a2a2a;
  color: #eee;
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
</style>
