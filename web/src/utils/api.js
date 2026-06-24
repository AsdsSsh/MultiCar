import { useAuthStore } from '../store/authStore.js'

const BASE_URL = ''  // 后端同源，无需跨域前缀

/**
 * 统一 API 请求封装
 * 自动附带 Authorization header
 */
async function request(url, options = {}) {
  const authStore = useAuthStore()
  const headers = {
    'Content-Type': 'application/json',
    ...options.headers
  }

  if (authStore.token) {
    headers['Authorization'] = `Bearer ${authStore.token}`
  }

  const res = await fetch(BASE_URL + url, {
    ...options,
    headers
  })

  const data = await res.json()

  if (!res.ok || !data.success) {
    const err = new Error(data.message || '请求失败')
    err.status = res.status
    err.data = data
    throw err
  }

  return data
}

export const api = {
  // 认证
  login(username, password) {
    return request('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password })
    })
  },

  register(username, password, confirmPassword, role) {
    return request('/api/auth/register', {
      method: 'POST',
      body: JSON.stringify({ username, password, confirmPassword, role })
    })
  },

  whoami() {
    return request('/api/auth/whoami')
  },

  // 管理员
  listUsers() {
    return request('/api/admin/users')
  },

  resetPassword(userId, newPassword) {
    return request(`/api/admin/users/${userId}/reset-password`, {
      method: 'POST',
      body: JSON.stringify({ newPassword })
    })
  },

  deleteUser(userId) {
    return request(`/api/admin/users/${userId}`, {
      method: 'DELETE'
    })
  },

  listRegistrations() {
    return request('/api/admin/registrations')
  },

  approveRegistration(requestId, assignedRole) {
    return request(`/api/admin/registrations/${requestId}/approve`, {
      method: 'POST',
      body: JSON.stringify({ assignedRole })
    })
  },

  rejectRegistration(requestId) {
    return request(`/api/admin/registrations/${requestId}/reject`, {
      method: 'POST'
    })
  },

  // 地图配置
  listMaps() {
    return request('/api/config/list')
  },

  getMap(mapId) {
    return request(`/api/config/get/${mapId}`)
  },

  saveMapConfig(config) {
    return request('/api/config/save', {
      method: 'POST',
      body: JSON.stringify(config)
    })
  },

  updateMapConfig(mapId, config) {
    return request(`/api/config/${mapId}`, {
      method: 'PUT',
      body: JSON.stringify(config)
    })
  },

  deleteMapConfig(mapId) {
    return request(`/api/config/${mapId}`, {
      method: 'DELETE'
    })
  },

  getMapConfig() {
    return request('/api/config/get')
  }
}
