<template>
  <div ref="chartRef" style="width: 100%; height: 100%; min-height: 60px"></div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { queryMetric } from '../../../api/monitor'
import * as echarts from 'echarts'

const props = defineProps({
  config: { type: Object, required: true },
  globalHours: { type: Number, required: false, default: null }
})
const chartRef = ref(null)
let chart = null
let resizeObserver = null

async function fetchData () {
  const cfg = props.config
  if (!cfg?.targetId || !Array.isArray(cfg.metricKeys) || cfg.metricKeys.length === 0) return
  // 全局时间联动优先；未联动时用 widget 自身配置
  const hours = (props.globalHours ?? cfg.timeRangeHours) || 1
  const body = {
    targetIds: [cfg.targetId],
    metricKeys: cfg.metricKeys,
    startTime: formatTime(new Date(Date.now() - hours * 3600_000)),
    endTime: formatTime(new Date()),
    aggregation: 'avg',
    // 时间粒度：1h/6h 窗口按 1 分钟聚合；24h+ 按 1 小时
    step: hours >= 24 ? '1h' : (hours >= 6 ? '5m' : '1m')
  }
  const r = await queryMetric(body)
  // axios 拦截器已剥壳,r 即 data 本体 (List<Map>)
  const rows = Array.isArray(r) ? r : (r?.records || [])
  render(groupSeries(rows, cfg.metricKeys, cfg.targetId))
}

function groupSeries (rows, metricKeys, targetId) {
  // 时间精度：秒级（HH:mm:ss）
  const xsSet = new Set()
  rows.forEach(r => { if (r.targetId === targetId) xsSet.add(String(r.bucketTime).substring(11, 19)) })
  const xs = Array.from(xsSet).sort()
  const da = metricKeys.map(mk => {
    const matched = rows.filter(r => r.metricKey === mk && r.targetId === targetId)
      .sort((a, b) => String(a.bucketTime).localeCompare(String(b.bucketTime)))
    return {
      name: mk,
      type: 'line',
      showSymbol: false,
      smooth: true,
      data: xs.map(b => {
        const row = matched.find(x => String(x.bucketTime).substring(11, 19) === b)
        return row && row.value != null ? Number(row.value) : null
      })
    }
  })
  return { xs, series: da }
}

function render (g) {
  if (!chart && chartRef.value) chart = echarts.init(chartRef.value)
  if (!chart) return
  chart.setOption({
    grid: { left: 40, right: 12, top: 30, bottom: 32 },
    xAxis: { type: 'category', data: g.xs, axisLabel: { interval: 'auto', rotate: 45, fontSize: 10 } },
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

onMounted(() => {
  fetchData()
  // 容器大小变化时让 echarts 重新渲染
  if (window.ResizeObserver) {
    resizeObserver = new ResizeObserver(() => {
      if (chart) chart.resize()
    })
    if (chartRef.value) resizeObserver.observe(chartRef.value)
  }
})

onBeforeUnmount(() => {
  // 修复审查 #27：组件销毁时释放 echarts 实例与观察器
  if (resizeObserver) { resizeObserver.disconnect(); resizeObserver = null }
  if (chart) { chart.dispose(); chart = null }
})
watch(() => [props.config.targetId, props.config.metricKeys, props.config.timeRangeHours, props.globalHours], fetchData)
</script>
