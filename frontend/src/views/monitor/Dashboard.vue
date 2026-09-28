<template>
  <div>
    <!-- 指标卡片 -->
    <el-row :gutter="16">
      <el-col :span="6">
        <el-card shadow="hover">
          <template #header>监控对象</template>
          <div class="stat-number">{{ overview.targetCount ?? '-' }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <template #header>CPU 使用率（近1h均值）</template>
          <div class="stat-number">{{ overview.avgCpu != null ? overview.avgCpu.toFixed(1) + '%' : '-' }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <template #header>内存使用率（近1h均值）</template>
          <div class="stat-number">{{ overview.avgMem != null ? overview.avgMem.toFixed(1) + '%' : '-' }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <template #header>24h 告警</template>
          <div class="stat-number">{{ overview.alert24h ?? '-' }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 近 1h 趋势图 -->
    <el-card shadow="hover" style="margin-top: 16px">
      <template #header>本机 CPU / 内存 · 近 1 小时趋势</template>
      <BaseChart :option="trendOption" height="360px" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import BaseChart from '../../components/BaseChart.vue'
import { getOverview, queryMetric } from '../../api/monitor'
import { minutesAgo, nowString } from '../../utils/time'

const overview = ref({})
const trendOption = ref({})
let timer = null

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
  trendOption.value = {
    tooltip: { trigger: 'axis' },
    legend: { data: Object.keys(series) },
    xAxis: { type: 'time' },
    yAxis: { type: 'value', max: 100 },
    series: Object.keys(series).map(k => ({
      name: k,
      type: 'line',
      smooth: true,
      showSymbol: false,
      data: series[k]
    }))
  }
}

onMounted(() => {
  loadOverview()
  loadTrend()
  timer = setInterval(loadTrend, 30_000)
})

onBeforeUnmount(() => clearInterval(timer))
</script>

<style scoped>
.stat-number {
  font-size: 32px;
  font-weight: bold;
  color: #303133;
  text-align: center;
}
</style>
