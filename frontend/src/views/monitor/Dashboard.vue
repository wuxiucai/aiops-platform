<template>
  <div>
    <!-- 指标卡片 -->
    <el-row :gutter="16">
      <el-col :span="6" v-for="card in statCards" :key="card.label">
        <div class="stat-card">
          <div class="stat-icon" :style="{ background: card.bg }">
            <el-icon :size="22" :color="card.color"><component :is="card.icon" /></el-icon>
          </div>
          <div class="stat-info">
            <div class="stat-value">{{ card.value }}</div>
            <div class="stat-label">{{ card.label }}</div>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- 近 1h 趋势图 -->
    <el-card shadow="hover" style="margin-top: 16px">
      <template #header>
        <div class="trend-header">
          <span>本机 CPU / 内存 · 近 1 小时趋势</span>
          <RefreshSelector v-model="refreshMs" @change="onRefreshChange" />
        </div>
      </template>
      <BaseChart :option="trendOption" height="360px" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import BaseChart from '../../components/BaseChart.vue'
import RefreshSelector from '../../components/RefreshSelector.vue'
import { getOverview, queryMetric } from '../../api/monitor'
import { minutesAgo, nowString } from '../../utils/time'

const overview = ref({})
const trendOption = ref({})
let timer = null

// 自动刷新间隔（毫秒），0=暂停。localStorage 记忆上次选择
const REFRESH_KEY = 'aiops_dashboard_refresh_ms'
const refreshMs = ref(Number(localStorage.getItem(REFRESH_KEY)) || 30000)

function startTimer () {
  if (timer) { clearInterval(timer); timer = null }
  if (refreshMs.value > 0) {
    timer = setInterval(() => { loadOverview(); loadTrend() }, refreshMs.value)
  }
}
function onRefreshChange (ms) {
  localStorage.setItem(REFRESH_KEY, String(ms))
  startTimer()
}

const statCards = computed(() => [
  {
    label: '监控对象',
    value: overview.value.targetCount ?? '-',
    icon: 'Monitor',
    color: '#4361ee',
    bg: 'linear-gradient(135deg, #eef1fe 0%, #dfe6ff 100%)'
  },
  {
    label: 'CPU 使用率（近1h均值）',
    value: overview.value.avgCpu != null ? overview.value.avgCpu.toFixed(1) + '%' : '-',
    icon: 'Cpu',
    color: '#3ba272',
    bg: 'linear-gradient(135deg, #eafaf3 0%, #d6f3e5 100%)'
  },
  {
    label: '内存使用率（近1h均值）',
    value: overview.value.avgMem != null ? overview.value.avgMem.toFixed(1) + '%' : '-',
    icon: 'Memo',
    color: '#e6a23c',
    bg: 'linear-gradient(135deg, #fdf6ec 0%, #fae9cd 100%)'
  },
  {
    label: '24h 告警',
    value: overview.value.alert24h ?? '-',
    icon: 'BellFilled',
    color: '#f4496a',
    bg: 'linear-gradient(135deg, #feecf1 0%, #fcd8e0 100%)'
  }
])

async function loadOverview() {
  overview.value = await getOverview()
}

async function loadTrend() {
  const data = await queryMetric({
    targetIds: [1],
    metricKeys: ['cpu.usage', 'mem.usage'],
    startTime: minutesAgo(60),
    endTime: nowString(),
    aggregation: 'avg',
    step: '1m'
  })
  const series = {}
  data.forEach(row => {
    if (!series[row.metricKey]) series[row.metricKey] = []
    series[row.metricKey].push([row.bucketTime, row.value])
  })
  const palette = ['#4361ee', '#3ba272', '#e6a23c', '#f4496a']
  trendOption.value = {
    color: palette,
    grid: { top: 40, right: 24, bottom: 32, left: 48 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(28, 36, 60, 0.92)',
      borderWidth: 0,
      textStyle: { color: '#fff', fontSize: 12 },
      axisPointer: { type: 'line', lineStyle: { color: '#c3ccdd' } }
    },
    legend: {
      data: Object.keys(series),
      top: 4,
      icon: 'roundRect',
      itemWidth: 14,
      itemHeight: 4,
      textStyle: { color: '#5b6478', fontSize: 12 }
    },
    xAxis: {
      type: 'time',
      axisLine: { lineStyle: { color: '#dde3ee' } },
      axisTick: { show: false },
      axisLabel: { color: '#97a1b5', fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      max: 100,
      splitLine: { lineStyle: { color: '#eef1f6' } },
      axisLabel: { color: '#97a1b5', fontSize: 11 }
    },
    series: Object.keys(series).map((k, i) => ({
      name: k,
      type: 'line',
      smooth: true,
      showSymbol: false,
      lineStyle: { width: 2.5 },
      emphasis: { focus: 'series' },
      areaStyle: {
        color: {
          type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [
            { offset: 0, color: palette[i % palette.length] + '33' },
            { offset: 1, color: palette[i % palette.length] + '05' }
          ]
        }
      },
      data: series[k]
    }))
  }
}

onMounted(() => {
  loadOverview()
  loadTrend()
  startTimer()
})

onBeforeUnmount(() => { if (timer) clearInterval(timer) })
</script>

<style scoped>
/* 统计卡片 */
.stat-card {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 18px 20px;
  background: #fff;
  border: 1px solid #e9edf4;
  border-radius: 12px;
  box-shadow: 0 1px 3px rgba(21, 32, 71, 0.04);
  transition: box-shadow .25s ease, transform .25s ease;
}
.stat-card:hover {
  box-shadow: 0 8px 20px rgba(21, 32, 71, 0.08);
  transform: translateY(-3px);
}
.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.stat-value {
  font-size: 26px;
  font-weight: 700;
  color: #2b3245;
  line-height: 1.25;
  font-variant-numeric: tabular-nums;
}
.stat-label {
  font-size: 12.5px;
  color: #97a1b5;
  margin-top: 2px;
}

.trend-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
