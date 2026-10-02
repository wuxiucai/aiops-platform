<template>
  <div>
    <!-- 顶部检索条件 -->
    <el-card shadow="hover" style="margin-bottom: 12px">
      <template #header>
        <div class="header-bar">
          <span>日志检索</span>
          <el-switch v-model="showDsl" active-text="显示 DSL" inactive-text="" />
        </div>
      </template>
      <el-form inline label-width="auto">
        <el-form-item label="时间范围">
          <el-date-picker
            v-model="timeRange"
            type="datetimerange"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 340px"
          />
        </el-form-item>
        <el-form-item label="级别">
          <el-select v-model="query.levels" multiple collapse-tags placeholder="全部" style="width: 180px">
            <el-option label="INFO" value="INFO" />
            <el-option label="WARN" value="WARN" />
            <el-option label="ERROR" value="ERROR" />
            <el-option label="DEBUG" value="DEBUG" />
          </el-select>
        </el-form-item>
        <el-form-item label="服务">
          <el-select
            v-model="query.services"
            multiple
            filterable
            allow-create
            default-first-option
            collapse-tags
            placeholder="全部或输入"
            style="width: 220px"
          >
            <el-option v-for="s in serviceOptions" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="短语 / 关键字" clearable style="width: 200px" @keyup.enter="onSearch" />
        </el-form-item>
        <el-form-item label="TraceId">
          <el-input v-model="query.traceId" clearable style="width: 200px" @keyup.enter="onSearch" />
        </el-form-item>
        <el-form-item>
          <el-button v-if="hasPerm('log:search:exec')" type="primary" :loading="loading" @click="onSearch">搜索</el-button>
          <el-button v-else type="primary" disabled title="缺少 log:search:exec 权限">搜索</el-button>
          <el-button @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>

      <!-- 直方图 -->
      <div v-if="histogramLoaded" ref="histogramWrapRef" style="margin-top: 4px">
        <BaseChart :option="histogramOption" height="220px" />
        <div class="histogram-tip">点击柱状可将时间窗口联动到该桶 ± 间隔</div>
      </div>
    </el-card>

    <!-- 结果表 -->
    <el-card shadow="hover">
      <el-table :data="rows" border stripe v-loading="loading">
        <el-table-column prop="time" label="时间" width="180" />
        <el-table-column label="级别" width="90">
          <template #default="{ row }">
            <el-tag :type="levelType(row.level)">{{ row.level }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="service" label="服务" width="140" show-overflow-tooltip />
        <el-table-column label="消息" min-width="320">
          <template #default="{ row }">
            <div class="msg-cell" v-html="highlight(row.message)"></div>
          </template>
        </el-table-column>
        <el-table-column label="TraceId" width="220">
          <template #default="{ row }">
            <el-link v-if="row.traceId" type="primary" @click="onJumpTrace(row.traceId)">{{ row.traceId }}</el-link>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="上下文" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="info" @click="onContext(row)">上下文</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        style="margin-top: 12px; justify-content: flex-end"
        @change="onSearch"
      />
    </el-card>

    <!-- DSL 展示 -->
    <el-dialog v-model="dslVisible" title="DSL 查询（论文截图用）" width="900px" destroy-on-close>
      <pre class="dsl-box">{{ dsl }}</pre>
    </el-dialog>

    <!-- 上下文 -->
    <el-dialog v-model="ctxVisible" title="日志上下文（前后 5 分钟 · 同服务 · 20 条）" width="1000px" destroy-on-close>
      <el-alert
        v-if="ctxAnchor"
        :title="`锚点：${ctxAnchor.time} · ${ctxAnchor.service}`"
        type="info"
        :closable="false"
        style="margin-bottom: 12px"
      />
      <el-table :data="ctxRows" border stripe max-height="480" v-loading="ctxLoading">
        <el-table-column prop="time" label="时间" width="180" />
        <el-table-column prop="level" label="级别" width="90">
          <template #default="{ row }">
            <el-tag :type="levelType(row.level)">{{ row.level }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="service" label="服务" width="140" />
        <el-table-column prop="message" label="消息" min-width="320" show-overflow-tooltip />
        <el-table-column prop="traceId" label="TraceId" width="220" show-overflow-tooltip />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, watch, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import request from '../../utils/request'
import { searchLogs, searchHistogram } from '../../api/log'
import { useUserStore } from '../../store/user'
import BaseChart from '../../components/BaseChart.vue'

const userStore = useUserStore()
const hasPerm = code => userStore.hasPerm(code)

// ============ 查询条件 ============
const timeRange = ref([])
const query = reactive({
  levels: [],
  services: [],
  keyword: '',
  traceId: '',
  page: 1,
  size: 20
})

function defaultTimeRange() {
  const end = new Date()
  const start = new Date(end.getTime() - 60 * 60 * 1000)
  return [formatTime(start), formatTime(end)]
}
function formatTime(d) {
  const pad = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}
timeRange.value = defaultTimeRange()

// ============ 数据源 & 服务候选 ============
const datasourceId = 1
const indexConfigId = 1
const serviceOptions = ref([])

async function loadServiceOptions() {
  try {
    const raw = await request.get(`/api/es/datasource/${datasourceId}/fields`, {
      params: { indexPattern: 'aiops-log-*' }
    })
    // 字段候选可能是数组或 { service: [...] }
    let candidates = []
    if (Array.isArray(raw)) {
      candidates = raw
    } else if (raw && Array.isArray(raw.service)) {
      candidates = raw.service
    } else if (raw && Array.isArray(raw.services)) {
      candidates = raw.services
    } else if (raw && Array.isArray(raw.fields)) {
      candidates = raw.fields.filter(f => /service/i.test(f))
    }
    serviceOptions.value = candidates.filter(Boolean)
  } catch (e) {
    // 不阻塞主流程
  }
}

// ============ 检索执行 ============
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const dsl = ref('')

function buildParams() {
  const [startTime, endTime] = timeRange.value || []
  return {
    datasourceId,
    indexConfigId,
    startTime,
    endTime,
    levels: query.levels || [],
    services: query.services || [],
    keyword: query.keyword || '',
    traceId: query.traceId || '',
    page: query.page,
    size: query.size,
    analyzer: 'standard'
  }
}

async function onSearch() {
  if (!timeRange.value || timeRange.value.length !== 2) {
    ElMessage.warning('请选择时间范围')
    return
  }
  loading.value = true
  try {
    const params = buildParams()
    const data = await searchLogs(params)
    rows.value = (data && data.records) || []
    total.value = (data && data.total) || 0
    dsl.value = (data && data.dsl) || ''
    if (dsl.value && showDsl.value) dslVisible.value = true
    await loadHistogram()
  } finally {
    loading.value = false
  }
}

function onReset() {
  query.levels = []
  query.services = []
  query.keyword = ''
  query.traceId = ''
  query.page = 1
  query.size = 20
  timeRange.value = defaultTimeRange()
  onSearch()
}

function onJumpTrace(traceId) {
  query.traceId = traceId
  query.page = 1
  onSearch()
}

// ============ 关键字高亮 ============
function escapeHtml(s) {
  if (s === null || s === undefined) return ''
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}
function escapeRegExp(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
function highlight(msg) {
  const safe = escapeHtml(msg)
  const kw = (query.keyword || '').trim()
  if (!kw) return safe
  try {
    const re = new RegExp('(' + escapeRegExp(escapeHtml(kw)) + ')', 'gi')
    return safe.replace(re, '<mark>$1</mark>')
  } catch (e) {
    return safe
  }
}

// ============ 直方图 ============
const showDsl = ref(false)
const dslVisible = ref(false)
const histogramWrapRef = ref(null)
const histogramLoaded = ref(false)
const histogramOption = ref({})
let histogramRaw = []
let histogramChart = null

async function loadHistogram() {
  try {
    const params = buildParams()
    const data = await searchHistogram(params)
    histogramRaw = (data && data.buckets) || []
    const times = histogramRaw.map(b => b.time)
    const counts = histogramRaw.map(b => b.count)
    histogramOption.value = {
      tooltip: { trigger: 'axis' },
      grid: { left: 40, right: 20, top: 20, bottom: 30 },
      xAxis: { type: 'category', data: times },
      yAxis: { type: 'value' },
      series: [{
        name: '日志量',
        type: 'bar',
        data: times.map((t, i) => [t, counts[i]]),
        itemStyle: { color: '#4361ee', borderRadius: [4, 4, 0, 0] }
      }]
    }
    histogramLoaded.value = true
    await nextTick()
    bindHistogramClick()
  } catch (e) {
    // 直方图失败不影响明细
    histogramLoaded.value = false
  }
}

function bindHistogramClick() {
  if (!histogramWrapRef.value) return
  const dom = histogramWrapRef.value.querySelector('div')
  if (!dom) return
  const chart = echarts.getInstanceByDom(dom)
  if (!chart) return
  if (histogramChart && histogramChart !== chart) {
    histogramChart.off('click')
  }
  histogramChart = chart
  chart.off('click')
  chart.on('click', ev => {
    const t = ev && ev.value && ev.value[0]
    if (!t) return
    const bucketTime = new Date(String(t).replace(' ', 'T'))
    if (isNaN(bucketTime.getTime())) return
    const interval = computeBucketInterval()
    const start = new Date(bucketTime.getTime() - interval)
    const end = new Date(bucketTime.getTime() + interval)
    timeRange.value = [formatTime(start), formatTime(end)]
    query.page = 1
    onSearch()
  })
}

// 桶间隔：取相邻两个 bucket 的时间差，找不到则默认 1 分钟
function computeBucketInterval() {
  if (histogramRaw.length >= 2) {
    const a = new Date(String(histogramRaw[0].time).replace(' ', 'T'))
    const b = new Date(String(histogramRaw[1].time).replace(' ', 'T'))
    const diff = Math.abs(b.getTime() - a.getTime())
    if (diff > 0) return diff
  }
  return 60 * 1000
}

watch(showDsl, v => {
  if (v && dsl.value) dslVisible.value = true
})

// ============ 上下文 ============
const ctxVisible = ref(false)
const ctxLoading = ref(false)
const ctxRows = ref([])
const ctxAnchor = ref(null)

async function onContext(row) {
  ctxVisible.value = true
  ctxLoading.value = true
  ctxAnchor.value = row
  ctxRows.value = []
  try {
    const anchorTime = new Date(String(row.time).replace(' ', 'T'))
    const fiveMin = 5 * 60 * 1000
    const start = new Date(anchorTime.getTime() - fiveMin)
    const end = new Date(anchorTime.getTime() + fiveMin)
    const data = await searchLogs({
      datasourceId,
      indexConfigId,
      startTime: formatTime(start),
      endTime: formatTime(end),
      levels: [],
      services: row.service ? [row.service] : [],
      keyword: '',
      traceId: '',
      page: 1,
      size: 20,
      analyzer: 'standard'
    })
    ctxRows.value = (data && data.records) || []
  } finally {
    ctxLoading.value = false
  }
}

// ============ 工具 ============
function levelType(l) {
  if (l === 'ERROR' || l === 'FATAL') return 'danger'
  if (l === 'WARN') return 'warning'
  if (l === 'DEBUG' || l === 'TRACE') return 'info'
  return 'success'
}

onMounted(() => {
  loadServiceOptions()
  onSearch()
})
</script>

<style scoped>
.header-bar { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px }
.histogram-tip {
  font-size: 12px;
  color: #909399;
  text-align: right;
  margin-top: 4px;
}
.msg-cell :deep(mark) {
  background: #ffe58f;
  padding: 0 2px;
  border-radius: 2px;
}
.dsl-box {
  background: #f5f7fa;
  padding: 12px;
  border-radius: 4px;
  font-size: 12px;
  max-height: 480px;
  overflow: auto;
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
