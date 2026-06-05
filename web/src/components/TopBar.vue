<script setup>
defineProps({
  title: { type: String, default: '变电站巡检仿真系统' },
  connected: { type: Boolean, default: false }
})
defineEmits(['open-config', 'reset'])
</script>

<template>
  <header class="topbar">
    <div class="brand">
      <span class="logo">🔆</span>
      <h1>{{ title }}</h1>
      <span class="conn" :class="{ on: connected }">
        <span class="dot"></span>{{ connected ? '已连接' : '未连接' }}
      </span>
    </div>
    <div class="actions">
      <button class="btn" @click="$emit('open-config')">⚙ 配置</button>
      <button class="btn" @click="$emit('reset')">↺ 重置</button>
    </div>
  </header>
</template>

<style scoped>
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
  gap: 12px;
}
.logo {
  font-size: 20px;
}
h1 {
  font-size: 17px;
  font-weight: 600;
  color: #fff;
}
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
.conn.on {
  color: #81c784;
}
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: currentColor;
}
.actions {
  display: flex;
  gap: 8px;
}
.btn {
  background: #3a3a3a;
  color: #eee;
  border: 1px solid #4a4a4a;
  border-radius: 6px;
  padding: 6px 14px;
  font-size: 13px;
  transition: background 0.15s;
}
.btn:hover {
  background: #4a4a4a;
}
</style>
