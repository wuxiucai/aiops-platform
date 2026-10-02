<template>
  <div ref="chartRef" style="width: 100%; height: 100%; min-height: 60px"></div>
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
  if (!cfg?.targetId || !Array.isArray(cfg.metricKeys) || cfg.metricKeys.length === 0) return
  const hours = cfg.timeRangeHours || 1
  const body = {
    targetIds: [cfg.targetId],
    metricKeys: cfg.metricKeys,
    startTime: formatTime(new Date(Date.now() - hours * 3600_000)),
    endTime: formatTime(new Date()),
    aggregation: 'avg',
    step: hours >= 24 ? '1h' : '5m'
  }
  const r = await request.post('/api/monitor/metric/query', body)
  if (r.code !== 200) return
  render(groupSeries(r.data || [], cfg.metricKeys, cfg.targetId))
}

function groupSeries (rows, metricKeys, targetId) {
  const xsSet = new Set()
  rows.forEach(r => { if (r.target_id === targetId) xsSet.add(String(r.bucket).substring(11, 16)) })
  const xs = Array.from(xsSet).sort()
  const da = metricKeys.map(mk => {
    const matched = rows.filter(r => r.metric_key === mk && r.target_id === targetId)
      .sort((a, b) => String(a.bucket).localeCompare(String(b.bucket)))
    return {
      name: mk,
      type: 'line',
      showSymbol: false,
      smooth: true,
      data: xs.map(b => {
        const row = matched.find(x => String(x.bucket).substring(11, 16) === b)
        return row && row.val != null ? Number(row.val) : null
      })
    }
  })
  return { xs, series: da }
}

function render (g) {
  if (!chart && chartRef.value) chart = echarts.init(chartRef.value)
  if (!chart) return
  chart.setOption({
    grid: { left: 32, right: 8, top: 30, bottom: 20 },
    xAxis: { type: 'category', data: g.xs },
    yAxis: { type: 'value' },
    legend: { top: 4, textStyle: { fontSize: 10 } },
    tooltip: { trigger: 'axis' },
    series: g.series
  }, true)
}

function formatTime (d) {
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

onMounted(fetchData)
watch(() => [props.config.targetId, props.config.metricKeys, props.config.timeRangeHours], fetchData)
</script>
