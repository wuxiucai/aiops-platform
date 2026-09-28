<template>
  <div>
    <el-card shadow="hover">
      <template #header>
        <div class="header-bar">
          <span>监控对象</span>
          <el-radio-group v-model="currentTargetId" @change="onTargetChange">
            <el-radio-button v-for="t in targets" :key="t.id" :value="t.id">
              {{ t.name }}
            </el-radio-button>
          </el-radio-group>
        </div>
      </template>

      <!-- 当前对象基本信息 -->
      <el-descriptions :column="4" border v-if="currentTarget">
        <el-descriptions-item label="名称">{{ currentTarget.name }}</el-descriptions-item>
        <el-descriptions-item label="类型">
          <el-tag :type="currentTarget.targetType === 'host' ? 'primary' : 'success'">
            {{ currentTarget.targetType }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="地址">{{ currentTarget.ip }}{{ currentTarget.port ? ':' + currentTarget.port : '' }}</el-descriptions-item>
        <el-descriptions-item label="日志服务名">{{ currentTarget.logServiceName || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <!-- 趋势图：host 显示 cpu/mem；service 显示 jvm.heap.usage / app.rt.avg -->
    <el-card shadow="hover" style="margin-top: 16px" v-if="currentTarget">
      <template #header>{{ currentTarget.name }} · 近 1 小时趋势</template>
      <BaseChart :option="trendOption" height="360px" v-loading="chartLoading" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import BaseChart from '../../components/BaseChart.vue'
import { listTarget, queryMetric } from '../../api/monitor'
import { minutesAgo, nowString } from '../../utils/time'

const targets = ref([])
const currentTargetId = ref(1)
const trendOption = ref({})
const chartLoading = ref(false)
let timer = null

const currentTarget = computed(() => targets.value.find(t => t.id === currentTargetId.value))

/** 每个目标展示的指标 key（按任务书 v2 §2.5 / M2 审查点选） */
function metricKeysFor(t) {
  if (t?.targetType === 'host') return ['cpu.usage', 'mem.usage']
  return ['jvm.heap.usage', 'app.rt.avg']
}

async function loadTargets() {
  targets.value = await listTarget()
  if (targets.value.length) {
    currentTargetId.value = targets.value[0].id
    loadTrend()
  }
}

async function loadTrend() {
  const t = currentTarget.value
  if (!t) return
  chartLoading.value = true
  try {
    const data = await queryMetric({
      targetIds: [t.id],
      metricKeys: metricKeysFor(t),
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
      yAxis: { type: 'value' },
      series: Object.keys(series).map(k => ({
        name: k,
        type: 'line',
        smooth: true,
        showSymbol: false,
        data: series[k]
      }))
    }
  } finally {
    chartLoading.value = false
  }
}

function onTargetChange() {
  loadTrend()
}

onMounted(() => {
  loadTargets()
  timer = setInterval(loadTrend, 30_000)
})

onBeforeUnmount(() => clearInterval(timer))
</script>

<style scoped>
.header-bar { display: flex; justify-content: space-between; align-items: center }
</style>
