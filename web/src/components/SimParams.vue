<script setup>
import { ALGORITHMS } from '../utils/constants.js'

defineProps({
  config: { type: Object, required: true },
  tick: { type: Number, default: 0 }
})

function algoLabel(v) {
  const a = ALGORITHMS.find((x) => x.value === v)
  return a ? a.label : v
}
</script>

<template>
  <section class="params">
    <h2>仿真参数</h2>
    <div class="row"><span>地图</span><b>{{ config.mapWidth }} × {{ config.mapHeight }}</b></div>
    <div class="row"><span>小车</span><b>{{ config.carCount }} 辆</b></div>
    <div class="row">
      <span>障碍密度</span><b>{{ Math.round(config.obstacleDensity * 100) }}%</b>
    </div>
    <div class="row"><span>路径算法</span><b>{{ algoLabel(config.algorithm) }}</b></div>
    <div class="row"><span>节拍</span><b class="tick">#{{ tick }}</b></div>
  </section>
</template>

<style scoped>
.params {
  background: #262626;
  border-radius: 8px;
  padding: 14px 16px;
}
h2 {
  font-size: 14px;
  color: #fff;
  margin-bottom: 12px;
}
.row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  padding: 5px 0;
  color: #aaa;
}
.row b {
  color: #eee;
  font-weight: 600;
}
.tick {
  color: #4fc3f7 !important;
  font-family: 'Consolas', monospace;
}
</style>
