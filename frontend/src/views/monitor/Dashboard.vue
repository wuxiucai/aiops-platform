<template>
  <div>
    <!-- 第一行：全局概览（4 张富卡片） -->
    <el-row :gutter="16">
      <!-- 监控对象 -->
      <el-col :span="6">
        <div class="stat-card">
          <div class="stat-top">
            <div class="stat-icon" :style="{ background: grad('#4361ee') }">
              <el-icon :size="22" color="#4361ee"><Monitor /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ ov.targetTotal ?? '-' }}</div>
              <div class="stat-label">监控对象</div>
            </div>
            <span class="chip chip-blue">主机 {{ ov.hostCount ?? 0 }} · 服务 {{ ov.serviceCount ?? 0 }}</span>
          </div>
          <div class="stat-foot">
            <div class="bar-row">
              <span class="bar-text">Agent 在线 {{ ov.agentOnline ?? 0 }}/{{ ov.targetTotal ?? 0 }}</span>
              <span class="bar-text strong">{{ agentOnlinePct }}%</span>
            </div>
            <div class="bar"><div class="bar-fill" :style="{ width: agentOnlinePct + '%', background: '#4361ee' }"></div></div>
          </div>
        </div>
      </el-col>

      <!-- CRITICAL 告警机器 -->
      <el-col :span="6">
        <div class="stat-card" :class="{ 'card-alert': criticalCount > 0 }">
          <div class="stat-top">
            <div class="stat-icon" :style="{ background: grad('#f4496a') }">
              <el-icon :size="22" color="#f4496a"><WarningFilled /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value" :style="criticalCount > 0 ? { color: '#e11d48' } : {}">
                {{ criticalCount }}<span class="stat-unit">台</span>
              </div>
              <div class="stat-label">CRITICAL 告警机器</div>
            </div>
            <span v-if="criticalCount > 0" class="chip chip-red pulse">活跃</span>
            <span v-else class="chip chip-green">正常</span>
          </div>
          <div class="stat-foot">
            <div class="sub-line">
              <span v-if="criticalCount > 0">涉及活跃告警 <b>{{ ov.pendingAlerts ?? 0 }}</b> 条，请尽快处理</span>
              <span v-else>全部机器无严重级别告警</span>
            </div>
          </div>
        </div>
      </el-col>

      <!-- 高 CPU 占用 -->
      <el-col :span="6">
        <div class="stat-card">
          <div class="stat-top">
            <div class="stat-icon" :style="{ background: grad('#e6a23c') }">
              <el-icon :size="22" color="#e6a23c"><Cpu /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ ov.highCpuCount ?? '-' }}<span class="stat-unit">台</span></div>
              <div class="stat-label">高 CPU 占用（≥{{ ov.cpuThreshold ?? 80 }}%）</div>
            </div>
            <span class="chip" :class="cpuHot ? 'chip-red' : 'chip-green'">{{ cpuHot ? '偏高' : '平稳' }}</span>
          </div>
          <div class="stat-foot">
            <div class="bar-row">
              <span class="bar-text ellipsis">峰值 {{ ov.topCpuName || '-' }}</span>
              <span class="bar-text strong" :style="{ color: barColor(ov.topCpuValue) }">{{ ov.topCpuValue != null ? ov.topCpuValue + '%' : '-' }}</span>
            </div>
            <div class="bar">
              <div class="bar-fill" :style="{ width: (ov.topCpuValue || 0) + '%', background: barColor(ov.topCpuValue) }"></div>
              <!-- 阈值刻度线 -->
              <div class="bar-mark" :style="{ left: (ov.cpuThreshold ?? 80) + '%' }"></div>
            </div>
          </div>
        </div>
      </el-col>

      <!-- 高内存占用 -->
      <el-col :span="6">
        <div class="stat-card">
          <div class="stat-top">
            <div class="stat-icon" :style="{ background: grad('#8b5cf6') }">
              <el-icon :size="22" color="#8b5cf6"><Memo /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ ov.highMemCount ?? '-' }}<span class="stat-unit">台</span></div>
              <div class="stat-label">高内存占用（≥{{ ov.memThreshold ?? 85 }}%）</div>
            </div>
            <span class="chip" :class="memHot ? 'chip-red' : 'chip-green'">{{ memHot ? '偏高' : '平稳' }}</span>
          </div>
          <div class="stat-foot">
            <div class="bar-row">
              <span class="bar-text ellipsis">峰值 {{ ov.topMemName || '-' }}</span>
              <span class="bar-text strong" :style="{ color: barColor(ov.topMemValue) }">{{ ov.topMemValue != null ? ov.topMemValue + '%' : '-' }}</span>
            </div>
            <div class="bar">
              <div class="bar-fill" :style="{ width: (ov.topMemValue || 0) + '%', background: barColor(ov.topMemValue) }"></div>
              <div class="bar-mark" :style="{ left: (ov.memThreshold ?? 85) + '%' }"></div>
            </div>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- 第二行：次要概览（4 张轻卡片） -->
    <el-row :gutter="16" style="margin-top: 14px">
      <el-col :span="6">
        <div class="mini-card">
          <el-icon :size="18" color="#3ba272"><CircleCheckFilled /></el-icon>
          <div class="mini-main">
            <div class="mini-value">{{ agentOnlinePct }}%</div>
            <div class="mini-label">Agent 在线率</div>
          </div>
          <div class="mini-side" :class="ov.agentOffline > 0 ? 'warn' : 'ok'">
            {{ ov.agentOffline > 0 ? ov.agentOffline + ' 台离线' : '全部在线' }}
          </div>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="mini-card">
          <el-icon :size="18" color="#f59e0b"><Odometer /></el-icon>
          <div class="mini-main">
            <div class="mini-value">{{ ov.openIncidents ?? '-' }}</div>
            <div class="mini-label">开放事件</div>
          </div>
          <div class="mini-side" :class="(ov.openIncidents ?? 0) > 0 ? 'warn' : 'ok'">
            {{ (ov.openIncidents ?? 0) > 0 ? '待处置' : '无进行中事件' }}
          </div>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="mini-card">
          <el-icon :size="18" color="#f4496a"><BellFilled /></el-icon>
          <div class="mini-main">
            <div class="mini-value">{{ ov.alert24h ?? '-' }}</div>
            <div class="mini-label">24h 告警</div>
          </div>
          <div class="mini-side" :class="(ov.pendingAlerts ?? 0) > 0 ? 'warn' : 'ok'">
            活跃 {{ ov.pendingAlerts ?? 0 }} 条
          </div>
        </div>
      </el-col>
      <el-col :span="6">
        <div class="mini-card">
          <el-icon :size="18" color="#4361ee"><TrendCharts /></el-icon>
          <div class="mini-main wide">
            <div class="mini-bars">
              <div class="mini-bar-row">
                <span class="mini-bar-label">CPU</span>
                <div class="mini-bar"><div class="bar-fill" :style="{ width: (ov.avgCpu || 0) + '%', background: barColor(ov.avgCpu) }"></div></div>
                <span class="mini-bar-val">{{ ov.avgCpu != null ? ov.avgCpu + '%' : '-' }}</span>
              </div>
              <div class="mini-bar-row">
                <span class="mini-bar-label">内存</span>
                <div class="mini-bar"><div class="bar-fill" :style="{ width: (ov.avgMem || 0) + '%', background: barColor(ov.avgMem) }"></div></div>
                <span class="mini-bar-val">{{ ov.avgMem != null ? ov.avgMem + '%' : '-' }}</span>
              </div>
            </div>
          </div>
          <div class="mini-side muted">1h 均值</div>
        </div>
      </el-col>
    </el-row>

    <!-- 目标健康矩阵：每台对象一个色块，点击下钻到监控对象 -->
    <el-card shadow="hover" style="margin-top: 16px">
      <template #header>
        <div class="trend-header">
          <span>目标健康矩阵 <span class="matrix-sub">按最新采样综合评级，点击色块下钻单机详情</span></span>
          <div class="legend">
            <span class="legend-item"><i class="dot dot-green"></i>正常</span>
            <span class="legend-item"><i class="dot dot-orange"></i>关注</span>
            <span class="legend-item"><i class="dot dot-red"></i>告警</span>
            <span class="legend-item"><i class="dot dot-gray"></i>无数据</span>
          </div>
        </div>
      </template>
      <div class="matrix">
        <div v-for="cell in matrix" :key="cell.targetId"
             class="matrix-cell" :class="'lv-' + cellLevel(cell)"
             :title="cellTooltip(cell)"
             @click="gotoTarget(cell)">
          <div class="cell-name" :title="cell.name">{{ cell.name }}</div>
          <div class="cell-type">
            {{ typeLabel(cell.targetType) }}
            <span v-if="!cell.agentOnline" class="cell-offline-inline">· 离线</span>
          </div>
          <div class="cell-metrics">
            <span>CPU {{ cell.cpu != null ? cell.cpu + '%' : '-' }}</span>
            <span>内存 {{ cell.mem != null ? cell.mem + '%' : '-' }}</span>
          </div>
          <div v-if="cell.pendingAlerts > 0" class="cell-badge" :class="{ crit: cell.criticalAlerts > 0 }">
            {{ cell.pendingAlerts }}
          </div>
        </div>
        <div v-if="!matrix.length" class="matrix-empty">暂无监控对象</div>
      </div>
    </el-card>

    <!-- 近 1h 趋势图（全 host 聚合） -->
    <el-card shadow="hover" style="margin-top: 16px">
      <template #header>
        <div class="trend-header">
          <span>全部主机 CPU / 内存 · 近 1 小时趋势（聚合均值）</span>
          <RefreshSelector v-model="refreshMs" @change="onRefreshChange" />
        </div>
      </template>
      <BaseChart :option="trendOption" height="360px" />
    </el-card>

    <!-- 活跃告警：全平台 pending 告警一览，随自动刷新同步 -->
    <el-card shadow="hover" style="margin-top: 16px">
      <template #header>
        <div class="trend-header">
          <span>活跃告警 <span class="matrix-sub">全平台待处理告警（{{ alertRows.length ? '最新 ' + alertRows.length + ' 条' : '暂无' }}）</span></span>
          <el-button size="small" link type="primary" @click="$router.push('/alert/record')">查看全部 →</el-button>
        </div>
      </template>
      <el-table :data="alertRows" size="small" stripe v-loading="alertLoading" empty-text="当前没有待处理告警">
        <el-table-column prop="level" label="级别" width="100">
          <template #default="{ row }">
            <el-tag :type="row.level === 'CRITICAL' ? 'danger' : (row.level === 'WARN' ? 'warning' : 'info')" size="small">
              {{ row.level }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="告警内容" min-width="320" show-overflow-tooltip/>
        <el-table-column prop="targetName" label="监控对象" width="140" show-overflow-tooltip/>
        <el-table-column prop="triggerCount" label="触发次数" width="90" align="right"/>
        <el-table-column prop="lastTriggerTime" label="最近触发" width="170"/>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import BaseChart from '../../components/BaseChart.vue'
import RefreshSelector from '../../components/RefreshSelector.vue'
import { queryMetric } from '../../api/monitor'
import { getStatOverview, getHealthMatrix } from '../../api/dashboard'
import { getRecordPage } from '../../api/alert'
import { minutesAgo, nowString } from '../../utils/time'

const router = useRouter()
const ov = ref({})
const matrix = ref([])
const trendOption = ref({})
const alertRows = ref([])
const alertLoading = ref(false)
let timer = null

// 自动刷新间隔（毫秒），0=暂停。localStorage 记忆上次选择
const REFRESH_KEY = 'aiops_dashboard_refresh_ms'
const refreshMs = ref(Number(localStorage.getItem(REFRESH_KEY)) || 30000)

function startTimer () {
  if (timer) { clearInterval(timer); timer = null }
  if (refreshMs.value > 0) {
    timer = setInterval(() => { loadOverview(); loadMatrix().then(loadTrend); loadAlerts() }, refreshMs.value)
  }
}
function onRefreshChange (ms) {
  localStorage.setItem(REFRESH_KEY, String(ms))
  startTimer()
}

const criticalCount = computed(() => ov.value.criticalAlertTargets ?? 0)
const cpuHot = computed(() => (ov.value.highCpuCount ?? 0) > 0)
const memHot = computed(() => (ov.value.highMemCount ?? 0) > 0)
const agentOnlinePct = computed(() => {
  const total = ov.value.targetTotal ?? 0
  if (!total) return 0
  return Math.round(((ov.value.agentOnline ?? 0) / total) * 100)
})

function grad (c) {
  return `linear-gradient(135deg, ${c}1f 0%, ${c}33 100%)`
}
/** 按数值严重度选条形颜色：<阈值绿，阈值~90 橙，≥90 红 */
function barColor (v) {
  if (v == null) return '#c3ccdd'
  if (v >= 90) return '#f4496a'
  if (v >= (ov.value.cpuThreshold ?? 80)) return '#e6a23c'
  return '#3ba272'
}

/* ============ 健康矩阵 ============ */
function typeLabel (t) {
  return { host: '主机', service: '服务', prometheus: 'Prometheus' }[t] || t
}
/** 健康档位：red(告警/严重负载) / orange(关注) / green(正常) / gray(无数据) */
function cellLevel (c) {
  const hasData = c.cpu != null || c.mem != null
  if (c.criticalAlerts > 0) return 'red'
  if (!hasData) return 'gray'
  if (c.cpu >= 90 || c.mem >= 90) return 'red'
  if (c.pendingAlerts > 0 || c.cpu >= (ov.value.cpuThreshold ?? 80) || c.mem >= (ov.value.memThreshold ?? 85)) return 'orange'
  return 'green'
}
function cellTooltip (c) {
  const parts = [`${c.name}（${typeLabel(c.targetType)}）`]
  parts.push(`CPU ${c.cpu != null ? c.cpu + '%' : '无数据'}，内存 ${c.mem != null ? c.mem + '%' : '无数据'}`)
  parts.push(c.pendingAlerts > 0 ? `活跃告警 ${c.pendingAlerts} 条（CRITICAL ${c.criticalAlerts}）` : '无活跃告警')
  parts.push(c.agentOnline ? 'Agent 在线' : 'Agent 离线')
  parts.push('点击下钻单机详情')
  return parts.join('\n')
}
function gotoTarget (c) {
  router.push({ path: '/monitor/target', query: { targetId: c.targetId } })
}

async function loadOverview () {
  ov.value = await getStatOverview()
}

async function loadMatrix () {
  matrix.value = await getHealthMatrix()
}

/** 活跃告警：pending 前若干条（pageWithNames 自带 targetName/ruleName） */
async function loadAlerts () {
  alertLoading.value = true
  try {
    const p = await getRecordPage({ current: 1, size: 8, status: 'pending' })
    alertRows.value = p.records || []
  } catch (e) { /* 拦截器已弹错 */ } finally {
    alertLoading.value = false
  }
}

/** 全 host 聚合曲线：取所有主机指标序列，按时间桶在前端求均值 */
async function loadTrend () {
  const hostIds = matrix.value.filter(c => c.targetType === 'host').map(c => c.targetId)
  if (!hostIds.length) { trendOption.value = {}; return }
  const data = await queryMetric({
    targetIds: hostIds,
    metricKeys: ['cpu.usage', 'mem.usage'],
    startTime: minutesAgo(60),
    endTime: nowString(),
    aggregation: 'avg',
    step: '1m'
  })
  // querySeries 按 target 分桶返回，这里聚合成单条均值曲线
  const acc = {} // metricKey -> bucketTime -> {sum, n}
  data.forEach(row => {
    if (row.value == null) return
    if (!acc[row.metricKey]) acc[row.metricKey] = {}
    const b = acc[row.metricKey][row.bucketTime] || { sum: 0, n: 0 }
    b.sum += Number(row.value)
    b.n += 1
    acc[row.metricKey][row.bucketTime] = b
  })
  const series = {}
  Object.keys(acc).forEach(k => {
    series[k] = Object.entries(acc[k])
      .map(([time, b]) => [time, Math.round((b.sum / b.n) * 100) / 100])
      .sort((a, b) => new Date(a[0]) - new Date(b[0]))
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

onMounted(async () => {
  loadOverview()
  loadAlerts()
  await loadMatrix()
  loadTrend()
  startTimer()
})

onBeforeUnmount(() => { if (timer) clearInterval(timer) })
</script>

<style scoped>
/* ============ 概览富卡片 ============ */
.stat-card {
  padding: 16px 18px 14px;
  background: #fff;
  border: 1px solid #e9edf4;
  border-radius: 12px;
  box-shadow: 0 1px 3px rgba(21, 32, 71, 0.04);
  transition: box-shadow .25s ease, transform .25s ease;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.stat-card:hover {
  box-shadow: 0 8px 20px rgba(21, 32, 71, 0.08);
  transform: translateY(-3px);
}
/* CRITICAL 机器 >0 时整卡红色警示描边 */
.stat-card.card-alert {
  border-color: #f8c9d4;
  background: linear-gradient(180deg, #fff 0%, #fff7f9 100%);
}
.stat-top {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}
.stat-icon {
  width: 44px;
  height: 44px;
  border-radius: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.stat-info { flex: 1; min-width: 0; }
.stat-value {
  font-size: 26px;
  font-weight: 700;
  color: #2b3245;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}
.stat-unit { font-size: 13px; font-weight: 500; color: #97a1b5; margin-left: 2px; }
.stat-label {
  font-size: 12.5px;
  color: #97a1b5;
  margin-top: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.stat-foot { display: flex; flex-direction: column; gap: 6px; }
.sub-line { font-size: 12px; color: #8a93a8; }
.sub-line b { color: #e11d48; font-weight: 600; }

/* 右上角状态 chip */
.chip {
  flex-shrink: 0;
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  font-weight: 500;
}
.chip-blue  { background: #eef1fe; color: #4361ee; }
.chip-red   { background: #feecf1; color: #e11d48; }
.chip-green { background: #eafaf3; color: #16a34a; }
.pulse { animation: pulse 1.6s ease-in-out infinite; }
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: .55; }
}

/* 细进度条（含阈值刻度） */
.bar {
  position: relative;
  height: 6px;
  border-radius: 3px;
  background: #eef1f6;
  overflow: visible;
}
.bar-fill {
  height: 100%;
  border-radius: 3px;
  transition: width .5s ease;
}
.bar-mark {
  position: absolute;
  top: -2px;
  width: 2px;
  height: 10px;
  border-radius: 1px;
  background: #d3d9e5;
}
.bar-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}
.bar-text { font-size: 11.5px; color: #97a1b5; }
.bar-text.strong { font-weight: 600; font-variant-numeric: tabular-nums; }
.ellipsis { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* ============ 第二行轻卡片 ============ */
.mini-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  background: #fff;
  border: 1px solid #e9edf4;
  border-radius: 12px;
}
.mini-main { flex: 1; min-width: 0; }
.mini-main.wide { flex: 1.4; }
.mini-value { font-size: 18px; font-weight: 700; color: #2b3245; line-height: 1.2; font-variant-numeric: tabular-nums; }
.mini-label { font-size: 11.5px; color: #97a1b5; margin-top: 1px; }
.mini-side {
  font-size: 11.5px;
  padding: 2px 8px;
  border-radius: 999px;
  white-space: nowrap;
}
.mini-side.ok   { background: #eafaf3; color: #16a34a; }
.mini-side.warn { background: #fdf6ec; color: #d97706; }
.mini-side.muted{ background: #f3f5f9; color: #8a93a8; }

/* 双 mini 条（CPU/内存均值） */
.mini-bars { display: flex; flex-direction: column; gap: 5px; }
.mini-bar-row { display: flex; align-items: center; gap: 8px; }
.mini-bar-label { font-size: 11px; color: #97a1b5; width: 26px; flex-shrink: 0; }
.mini-bar { flex: 1; height: 5px; border-radius: 3px; background: #eef1f6; overflow: hidden; }
.mini-bar .bar-fill { transition: width .5s ease; }
.mini-bar-val { font-size: 11px; color: #5b6478; width: 44px; text-align: right; font-variant-numeric: tabular-nums; }

.trend-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

/* ============ 目标健康矩阵 ============ */
.matrix-sub { font-size: 12px; color: #97a1b5; font-weight: 400; margin-left: 8px; }
.legend { display: flex; gap: 14px; }
.legend-item { display: flex; align-items: center; gap: 5px; font-size: 12px; color: #8a93a8; }
.dot { width: 9px; height: 9px; border-radius: 50%; display: inline-block; }
.dot-green  { background: #3ba272; }
.dot-orange { background: #e6a23c; }
.dot-red    { background: #f4496a; }
.dot-gray   { background: #c3ccdd; }

.matrix {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 12px;
}
.matrix-cell {
  position: relative;
  padding: 12px 14px;
  border-radius: 10px;
  border: 1px solid #e9edf4;
  border-left-width: 4px;
  cursor: pointer;
  transition: box-shadow .2s ease, transform .2s ease;
  background: #fff;
}
.matrix-cell:hover { box-shadow: 0 6px 16px rgba(21, 32, 71, 0.1); transform: translateY(-2px); }
.matrix-cell.lv-green  { border-left-color: #3ba272; background: #f7fcfa; }
.matrix-cell.lv-orange { border-left-color: #e6a23c; background: #fffaf2; }
.matrix-cell.lv-red    { border-left-color: #f4496a; background: #fff5f7; }
.matrix-cell.lv-gray   { border-left-color: #c3ccdd; background: #fafbfd; }

.cell-name {
  font-size: 13.5px;
  font-weight: 600;
  color: #2b3245;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding-right: 30px;
}
.cell-type { font-size: 11px; color: #97a1b5; margin: 2px 0 8px; }
.cell-metrics {
  display: flex;
  justify-content: space-between;
  font-size: 11.5px;
  color: #5b6478;
  font-variant-numeric: tabular-nums;
}
.cell-badge {
  position: absolute;
  top: 10px;
  right: 10px;
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  border-radius: 999px;
  background: #e6a23c;
  color: #fff;
  font-size: 11px;
  line-height: 20px;
  text-align: center;
  font-weight: 600;
}
.cell-badge.crit { background: #f4496a; }
/* 离线标识：行内显示在类型旁，避免绝对定位压住底部指标行 */
.cell-offline-inline { color: #e6a23c; font-weight: 600; }
.matrix-empty { grid-column: 1 / -1; text-align: center; color: #97a1b5; font-size: 13px; padding: 30px 0; }
</style>
