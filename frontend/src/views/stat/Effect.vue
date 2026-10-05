<template>
  <div class="effect-page">
    <el-card shadow="hover">
      <div class="page-head">
        <h2>平台效果指标</h2>
        <el-radio-group v-model="days" @change="load">
          <el-radio-button :value="7">近 7 天</el-radio-button>
          <el-radio-button :value="14">近 14 天</el-radio-button>
          <el-radio-button :value="30">近 30 天</el-radio-button>
        </el-radio-group>
      </div>

      <!-- KPI -->
      <el-row :gutter="16" v-loading="loading">
        <el-col :span="6">
          <div class="kpi">
            <div class="kpi-title">告警总数</div>
            <div class="kpi-val">{{ data.alert?.total ?? 0 }}</div>
            <div class="kpi-sub">pending {{ data.alert?.pending ?? 0 }} · claimed {{ data.alert?.claimed ?? 0 }}</div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="kpi">
            <div class="kpi-title">MTTA / 告警 MTTR</div>
            <div class="kpi-val">{{ fmtDur(data.alert?.mtta_sec) }} / {{ fmtDur(data.alert?.mttr_alert_sec) }}</div>
            <div class="kpi-sub">认领 · 解决</div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="kpi">
            <div class="kpi-title">事件总数</div>
            <div class="kpi-val">{{ data.incident?.total ?? 0 }}</div>
            <div class="kpi-sub">open {{ data.incident?.open ?? 0 }} · resolved {{ data.incident?.resolved ?? 0 }}</div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="kpi">
            <div class="kpi-title">事件 MTTR</div>
            <div class="kpi-val">{{ fmtDur(data.incident?.mttr_sec) }}</div>
            <div class="kpi-sub">误报率 {{ fmtPct(data.falsePositiveRate) }}</div>
          </div>
        </el-col>
      </el-row>
    </el-card>

    <el-row :gutter="16" style="margin-top: 16px">
      <el-col :span="12">
        <el-card shadow="hover">
          <template #header>告警按级别分布</template>
          <BaseChart :option="levelOption" height="260px" />
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="hover">
          <template #header>告警日趋势</template>
          <BaseChart :option="trendOption" height="260px" />
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="hover" style="margin-top: 16px">
      <template #header>
        <span>日志模板压缩 Top10</span>
        <span class="muted" style="margin-left: 12px; font-weight: normal">
          共 {{ data.template?.tpl_count ?? 0 }} 个模板 / {{ data.template?.hit_total ?? 0 }} 条日志命中
        </span>
      </template>
      <el-table :data="data.topTemplates || []" border stripe max-height="360">
        <el-table-column prop="id" label="ID" width="70"/>
        <el-table-column prop="service" label="服务" width="180"/>
        <el-table-column prop="total_count" label="命中" width="100" align="right"/>
        <el-table-column prop="template_text" label="模板" min-width="300" show-overflow-tooltip/>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import request from '../../utils/request'
import BaseChart from '../../components/BaseChart.vue'

const days = ref(7)
const loading = ref(false)
const data = ref({})

function fmtPct (v) {
  if (typeof v !== 'number' || !isFinite(v)) return '0%'
  return (v * 100).toFixed(1) + '%'
}
function fmtDur (sec) {
  if (sec == null || isNaN(sec)) return '-'
  sec = Math.round(sec)
  if (sec < 60) return sec + 's'
  if (sec < 3600) return Math.floor(sec / 60) + 'm' + (sec % 60) + 's'
  return Math.floor(sec / 3600) + 'h' + Math.floor((sec % 3600) / 60) + 'm'
}

async function load () {
  loading.value = true
  try {
    const r = await request.get('/api/stat/effect-metrics', { params: { days: days.value } })
    data.value = r || {}
  } finally { loading.value = false }
}

const levelOption = computed(() => {
  const list = data.value.alertByLevel || []
  return {
    tooltip: { trigger: 'item' },
    legend: { bottom: 0 },
    series: [{
      type: 'pie',
      radius: ['40%', '70%'],
      label: { formatter: '{b}: {c}' },
      data: list.map(x => ({ name: x.level || 'unknown', value: x.cnt }))
    }]
  }
})

const trendOption = computed(() => {
  const list = data.value.alertByDay || []
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['告警数', '已解决'] },
    xAxis: { type: 'category', data: list.map(x => x.d) },
    yAxis: { type: 'value' },
    series: [
      { name: '告警数', type: 'line', smooth: true, data: list.map(x => x.cnt), areaStyle: { opacity: 0.2 } },
      { name: '已解决', type: 'line', smooth: true, data: list.map(x => x.resolved) }
    ]
  }
})

onMounted(load)
</script>

<style scoped>
.effect-page { padding: 0; }
.page-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-head h2 { margin: 0; font-size: 20px; }
.kpi { padding: 14px; background: #f7f9fc; border-radius: 8px; }
.kpi-title { font-size: 13px; color: #909399; }
.kpi-val { font-size: 22px; font-weight: 700; color: #2b3245; margin: 6px 0; }
.kpi-sub { font-size: 12px; color: #97a1b5; }
.muted { color: #97a1b5; font-size: 12px; }
</style>
