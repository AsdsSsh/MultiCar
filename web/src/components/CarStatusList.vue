<script setup>
import { useSimulationStore } from '../store/simulationStore.js'
import { getCarColor } from '../utils/canvasDrawer.js'
import CarStatusItem from './CarStatusItem.vue'

defineProps({
  cars: { type: Array, default: () => [] }
})

const store = useSimulationStore()

function onHover(carId) {
  store.setActiveCar(carId)
}

function onLeave() {
  store.setActiveCar(null)
}
</script>

<template>
  <section class="car-list">
    <h2>小车状态 <small>({{ cars.length }})</small></h2>
    <p v-if="!cars.length" class="empty">暂无小车数据</p>
    <CarStatusItem
      v-for="(car, i) in cars"
      :key="car.carId"
      :car="car"
      :color="getCarColor(i)"
      :index="i + 1"
      :active="store.activeCarId === car.carId"
      @hover="onHover"
      @leave="onLeave"
    />
  </section>
</template>

<style scoped>
.car-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
h2 {
  font-size: 14px;
  color: #fff;
  margin-bottom: 4px;
}
h2 small {
  color: #888;
  font-weight: 400;
}
.empty {
  font-size: 13px;
  color: #777;
  padding: 12px 0;
  text-align: center;
}
</style>
