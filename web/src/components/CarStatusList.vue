<script setup>
import { useSimulationStore } from '../store/simulationStore.js'
import { getCarColor, extractCarNumber } from '../utils/canvasDrawer.js'
import CarStatusItem from './CarStatusItem.vue'

defineProps({
  cars: { type: Array, default: () => [] }
})

const emit = defineEmits(['delete-car', 'move-car'])

const store = useSimulationStore()

function onHover(carId) {
  store.setActiveCar(carId)
}

function onLeave() {
  store.setActiveCar(null)
}

function carColor(car) {
  const num = extractCarNumber(car.carId)
  return getCarColor(num - 1)
}
</script>

<template>
  <section class="car-list">
    <h2>小车状态 <small>({{ cars.length }})</small></h2>
    <p v-if="!cars.length" class="empty">暂无小车数据</p>
    <CarStatusItem
      v-for="car in cars"
      :key="car.carId"
      :car="car"
      :color="carColor(car)"
      :index="extractCarNumber(car.carId)"
      :active="store.activeCarId === car.carId"
      @hover="onHover"
      @leave="onLeave"
      @delete-car="emit('delete-car', $event)"
      @move-car="emit('move-car', $event)"
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
