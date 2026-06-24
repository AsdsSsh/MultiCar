import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuthStore } from '../store/authStore.js'

import LoginPage from '../views/LoginPage.vue'
import RegisterPage from '../views/RegisterPage.vue'
import AdminPage from '../views/AdminPage.vue'
import ConfiguratorPage from '../views/ConfiguratorPage.vue'
import UserPage from '../views/UserPage.vue'
import ReplayPage from '../views/ReplayPage.vue'

const routes = [
  { path: '/', redirect: '/login' },
  { path: '/login', name: 'Login', component: LoginPage, meta: { guest: true } },
  { path: '/register', name: 'Register', component: RegisterPage, meta: { guest: true } },
  {
    path: '/admin',
    name: 'Admin',
    component: AdminPage,
    meta: { requiresAuth: true, roles: ['ADMIN'] }
  },
  {
    path: '/configurator',
    name: 'Configurator',
    component: ConfiguratorPage,
    meta: { requiresAuth: true, roles: ['CONFIGURATOR', 'ADMIN'] }
  },
  {
    path: '/user',
    name: 'User',
    component: UserPage,
    meta: { requiresAuth: true, roles: ['USER', 'ADMIN'] }
  },
  {
    path: '/replay',
    name: 'Replay',
    component: ReplayPage,
    meta: { requiresAuth: true, roles: ['USER', 'ADMIN'] }
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  const authStore = useAuthStore()

  // 已登录用户访问访客页面 → 跳转到对应角色主页
  if (to.meta.guest && authStore.isLoggedIn) {
    next(getRoleHome(authStore.role))
    return
  }

  // 需要认证的页面
  if (to.meta.requiresAuth) {
    if (!authStore.isLoggedIn) {
      next('/login')
      return
    }
    // 角色检查
    if (to.meta.roles && !to.meta.roles.includes(authStore.role)) {
      next(getRoleHome(authStore.role))
      return
    }
  }

  next()
})

function getRoleHome(role) {
  switch (role) {
    case 'ADMIN': return '/admin'
    case 'CONFIGURATOR': return '/configurator'
    case 'USER': return '/user'
    default: return '/login'
  }
}

export default router
