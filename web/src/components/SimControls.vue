<script setup>
defineProps({
  isRunning: { type: Boolean, default: false }
})
defineEmits(['start', 'pause', 'step', 'reset'])
</script>

<template>
  <section class="controls">
    <button class="btn start" :disabled="isRunning" @click="$emit('start')">▶ 开始</button>
    <button class="btn pause" :disabled="!isRunning" @click="$emit('pause')">⏸ 暂停</button>
    <button class="btn step" :disabled="isRunning" @click="$emit('step')">⏭ 单步</button>
  </section>
</template>

<style scoped>
.controls {
  display: flex;
  gap: 8px;
}
.btn {
  flex: 1;
  border: none;
  border-radius: 6px;
  padding: 10px 0;
  font-size: 13px;
  font-weight: 600;
  color: #10242e;
  transition: opacity 0.15s;
}
.btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.btn.start {
  background: #81c784;
}
.btn.pause {
  background: #ffd54f;
}
.btn.step {
  background: #4fc3f7;
}
.btn:not(:disabled):hover {
  filter: brightness(1.1);
}
</style>
