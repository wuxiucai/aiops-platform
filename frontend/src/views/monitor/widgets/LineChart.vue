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
    step: hours >= 24 ? '1h' : '5m'
  }
  const r = await request.post('/api/monitor/metric/query', body)
  if (r.code !== 200) return
  // 返回 List<Map>: [{bucketTime, targetId, metricKey, value}, ...]
  const rows = (r.data || []).filter(row => row.metricKey === cfg.metricKey && row.targetId === cfg.targetId)
  rows.sort((a, b) => String(a.bucketTime).localeCompare(String(b.bucketTime)))
  renderChart(rows)
}

function renderChart (rows) {
  if (!chart && chartRef.value) {
    chart = echarts.init(chartRef.value)
  }
  if (!chart) return
  const xs = rows.map(r => String(r.bucketTime).substring(11, 16))
  const ys = rows.map(r => r.value != null ? Number(r.value) : 0)
  chart.setOption({
    grid: { left: 32, right: 8, top: 8, bottom: 20 },
    xAxis: { type: 'category', data: xs, axisLabel: { interval: 'auto' } },
    yAxis: { type: 'value' },
    series: [{ type: 'line', data: ys, smooth: true, showSymbol: false, areaStyle: {} }],
    tooltip: { trigger: 'axis' }
  }, true)
}

function formatTime (d) {
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

onMounted(fetchData)
watch(() => [props.config.targetId, props.config.metricKey, props.config.timeRangeHours], fetchData)
</script>

<style scoped>
.line-chart { min-height: 60px; }
</style>
