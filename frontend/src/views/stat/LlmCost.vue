<template>
  <div class="llmcost-page">
    <el-card shadow="hover">
      <div class="page-head">
        <h2>LLM 成本统计</h2>
        <el-radio-group v-model="days" @change="load">
          <el-radio-button :value="7">近 7 天</el-radio-button>
          <el-radio-button :value="14">近 14 天</el-radio-button>
          <el-radio-button :value="30">近 30 天</el-radio-button>
        </el-radio-group>
      </div>

      <el-row :gutter="16" v-loading="loading">
        <el-col :span="6">
          <div class="kpi">
            <div class="kpi-title">调用总数</div>
            <div class="kpi-val">{{ data.overview?.total_calls ?? 0 }}</div>
            <div class="kpi-sub">ok {{ data.overview?.ok_calls ?? 0 }} · failed {{ data.overview?.failed_calls ?? 0 }}</div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="kpi">
            <div class="kpi-title">总 tokens</div>
            <div class="kpi-val">{{ fmtNum(data.overview?.total_tokens) }}</div>
            <div class="kpi-sub">prompt {{ fmtNum(data.overview?.prompt_tokens) }} / completion {{ fmtNum(data.overview?.completion_tokens) }}</div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="kpi">
            <div class="kpi-title">平均延迟</div>
            <div class="kpi-val">{{ fmtMs(data.overview?.avg_latency_ms) }}</div>
            <div class="kpi-sub">max {{ fmtMs(data.overview?.max_latency_ms) }}</div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="kpi">
            <div class="kpi-title">估算成本</div>
            <div class="kpi-val">${{ estCost }}</div>
            <div class="kpi-sub">按 DeepSeek $0.14/1M input + $0.28/1M output</div>
          </div>
        </el-col>
      </el-row>
    </el-card>

    <el-row :gutter="16" style="margin-top: 16px">
      <el-col :span="12">
        <el-card shadow="hover">
          <template #header>按场景（scene_code） tokens</template>
          <BaseChart :option="sceneOption" height="280px" />
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="hover">
          <template #header>tokens 日趋势</template>
          <BaseChart :option="trendOption" height="280px" />
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-top: 16px">
      <el-col :span="12">
        <el-card shadow="hover">
          <template #header>按 provider</template>
          <el-table :data="data.byProvider || []" border stripe max-height="280">
            <el-table-column prop="provider_id" label="Provider ID" width="120"/>
            <el-table-column prop="calls" label="调用" width="100" align="right"/>
            <el-table-column prop="total_tokens" label="tokens" align="right"/>
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="hover">
          <template #header>慢调用 Top10</template>
          <el-table :data="data.slowTop10 || []" border stripe max-height="280">
            <el-table-column prop="id" label="ID" width="60"/>
            <el-table-column prop="scene_code" label="场景" width="160"/>
            <el-table-column prop="latency_ms" label="延迟(ms)" width="100" align="right"/>
            <el-table-column prop="total_tokens" label="tokens" width="90" align="right"/>
            <el-table-column prop="status" label="状态" width="80"/>
            <el-table-column prop="create_time" label="时间" show-overflow-tooltip/>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import request from '../../utils/request'
import BaseChart from '../../components/BaseChart.vue'

const days = ref(7)
const loading = ref(false)
const data = ref({})

function fmtNum (n) {
  n = Number(n || 0)
  if (n >= 1e6) return (n / 1e6).toFixed(2) + 'M'
  if (n >= 1e3) return (n / 1e3).toFixed(1) + 'k'
  return String(n)
}
function fmtMs (ms) {
  ms = Number(ms || 0)
  if (ms >= 1000) return (ms / 1000).toFixed(2) + 's'
  return Math.round(ms) + 'ms'
}

const estCost = computed(() => {
  const o = data.value.overview || {}
  const inCost = (o.prompt_tokens || 0) * 0.14 / 1e6
  const outCost = (o.completion_tokens || 0) * 0.28 / 1e6
  return (inCost + outCost).toFixed(4)
})

const sceneOption = computed(() => {
  const list = data.value.byScene || []
  return {
    tooltip: { trigger: 'item' },
    legend: { bottom: 0 },
    series: [{
      type: 'pie',
      radius: ['40%', '70%'],
      label: { formatter: '{b}: {c}' },
      data: list.map(x => ({ name: x.scene_code || 'unknown', value: x.total_tokens }))
    }]
  }
})

const trendOption = computed(() => {
  const list = data.value.byDay || []
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['tokens', '调用数'] },
    xAxis: { type: 'category', data: list.map(x => x.d) },
    yAxis: [
      { type: 'value', name: 'tokens' },
      { type: 'value', name: 'calls' }
    ],
    series: [
      { name: 'tokens', type: 'bar', data: list.map(x => x.total_tokens) },
      { name: '调用数', type: 'line', yAxisIndex: 1, smooth: true, data: list.map(x => x.calls) }
    ]
  }
})

async function load () {
  loading.value = true
  try {
    const r = await request.get('/api/stat/llm-cost', { params: { days: days.value } })
    data.value = r || {}
  } finally { loading.value = false }
}

onMounted(load)
</script>

<style scoped>
.llmcost-page { padding: 0; }
.page-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-head h2 { margin: 0; font-size: 20px; }
.kpi { padding: 14px; background: #f7f9fc; border-radius: 8px; }
.kpi-title { font-size: 13px; color: #909399; }
.kpi-val { font-size: 22px; font-weight: 700; color: #2b3245; margin: 6px 0; }
.kpi-sub { font-size: 12px; color: #97a1b5; }
</style>
