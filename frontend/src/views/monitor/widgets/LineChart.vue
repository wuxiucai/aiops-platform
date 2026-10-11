<template>
  <div ref="chartRef" class="line-chart" style="width: 100%; height: 100%"></div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import request from '../../../utils/request'
import * as echarts from 'echarts'

const props = defineProps({ config: { type: Object, required: true } })
const chartRef = ref(null)
let chart = null

async function fetchData () {
  const cfg = props.config
  if (!cfg?.targetId || !cfg?.metricKey) return
  const hours = cfg.timeRangeHours || 1
  const body = {
    targetIds: [cfg.targetId],
    metricKeys: [cfg.metricKey],
    startTime: formatTime(new Date(Date.now() - hours * 3600_000)),
    endTime: formatTime(new Date()),
    aggregation: 'avg',
    // 时间粒度：1h 窗口按 1 分钟聚合（~60 点，肉眼可见秒级精度）；24h+ 仍按 1h
    step: hours >= 24 ? '1h' : (hours >= 6 ? '5m' : '1m')
  }
  const r = await request.post('/api/monitor/metric/query', body)
  // axios 拦截器已剥壳，r 即 data 本体（List<Map>）
  const rows = (Array.isArray(r) ? r : (r?.records || r?.list || []))
    .filter(row => row.metricKey === cfg.metricKey && row.targetId === cfg.targetId)
  rows.sort((a, b) => String(a.bucketTime).localeCompare(String(b.bucketTime)))
  renderChart(rows)
}

function renderChart (rows) {
  if (!chart && chartRef.value) {
    chart = echarts.init(chartRef.value)
  }
  if (!chart) return
  // 时间精度：秒级（截取 HH:mm:ss，第 11-19 位）
  const xs = rows.map(r => String(r.bucketTime).substring(11, 19))
  const ys = rows.map(r => r.value != null ? Number(r.value) : 0)
  chart.setOption({
    grid: { left: 40, right: 12, top: 12, bottom: 32 },
    xAxis: {
      type: 'category',
      data: xs,
      axisLabel: { interval: 'auto', rotate: 45, fontSize: 10 }
    },
    yAxis: { type: 'value' },
    series: [{ type: 'line', data: ys, smooth: true, showSymbol: false, areaStyle: {} }],
    tooltip: { trigger: 'axis' }
  }, true)
}

function formatTime (d) {
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

onMounted(() => {
  fetchData()
  // 容器大小变化时让 echarts 重新渲染
  if (window.ResizeObserver) {
    const ro = new ResizeObserver(() => {
      if (chart) chart.resize()
    })
    if (chartRef.value) ro.observe(chartRef.value)
  }
})
watch(() => [props.config.targetId, props.config.metricKey, props.config.timeRangeHours], fetchData)
</script>

<style scoped>
.line-chart { width: 100%; height: 100%; display: flex; flex-direction: column }
</style>
