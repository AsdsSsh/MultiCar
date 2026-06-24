import { defineStore } from 'pinia'

const TOKEN_KEY = 'blackbox_token'
const USER_KEY = 'blackbox_user'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    username: '',
    role: '',
    userId: ''
  }),

  getters: {
    isLoggedIn: (state) => !!state.token && !!state.username
  },

  actions: {
    setAuth(token, username, role, userId) {
      this.token = token
      this.username = username
      this.role = role
      this.userId = userId
      localStorage.setItem(TOKEN_KEY, token)
      localStorage.setItem(USER_KEY, JSON.stringify({ username, role, userId }))
    },

    loadFromStorage() {
      const token = localStorage.getItem(TOKEN_KEY)
      const userStr = localStorage.getItem(USER_KEY)
      if (token && userStr) {
        try {
          const user = JSON.parse(userStr)
          this.token = token
          this.username = user.username || ''
          this.role = user.role || ''
          this.userId = user.userId || ''
        } catch (e) {
          this.clearAuth()
        }
      }
    },

    clearAuth() {
      this.token = ''
      this.username = ''
      this.role = ''
      this.userId = ''
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USER_KEY)
    },

    logout() {
      this.clearAuth()
    }
  }
})
