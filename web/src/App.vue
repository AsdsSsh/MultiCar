<script setup>
import { onBeforeUnmount } from 'vue'
import { useAuthStore } from './store/authStore.js'

const authStore = useAuthStore()
authStore.loadFromStorage()
</script>

<template>
  <router-view />
</template>

<style>
/* 全局样式（非 scoped） */
* {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}
html,
body,
#app {
  height: 100%;
}
body {
  font-family: -apple-system, 'Segoe UI', 'Microsoft YaHei', sans-serif;
  background: #161616;
  color: #e8e8e8;
  -webkit-font-smoothing: antialiased;
}
button {
  font-family: inherit;
  cursor: pointer;
}
</style>
