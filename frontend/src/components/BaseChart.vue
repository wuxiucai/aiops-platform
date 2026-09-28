<template>
  <div ref="chartRef" :style="{ width: '100%', height: height }" />
</template>

<script setup>
// ECharts 统一封装：传入 option 即可
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  option: { type: Object, required: true },
  height: { type: String, default: '320px' }
})

const chartRef = ref(null)
let chart = null

onMounted(() => {
  chart = echarts.init(chartRef.value)
  chart.setOption(props.option)
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  if (chart) chart.dispose()
})

watch(() => props.option, val => {
  if (chart && val) chart.setOption(val, true)
}, { deep: true })

function handleResize() {
  if (chart) chart.resize()
}
</script>
