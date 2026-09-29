<template>
  <div>
    <el-card shadow="hover">
      <template #header>
        <div class="header-bar">
          <span>日志模板</span>
          <div>
            <el-radio-group v-model="query.status" @change="load" style="margin-right: 8px">
              <el-radio-button :value="null">全部</el-radio-button>
              <el-radio-button :value="0">正常</el-radio-button>
              <el-radio-button :value="1">关注</el-radio-button>
              <el-radio-button :value="2">忽略</el-radio-button>
            </el-radio-group>
            <el-select
              v-model="query.service"
              placeholder="服务"
              clearable
              filterable
              style="width: 160px; margin-right: 8px"
            >
              <el-option v-for="s in serviceOptions" :key="s" :label="s" :value="s" />
            </el-select>
            <el-input
              v-model="query.keyword"
              placeholder="模板关键字"
              clearable
              style="width: 200px; margin-right: 8px"
              @keyup.enter="load"
            />
            <el-button type="primary" @click="load">查询</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" border stripe v-loading="loading">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div style="padding: 8px 24px">
              <div style="font-weight: bold; margin-bottom: 4px">示例日志</div>
              <pre class="sample-box">{{ row.sampleLog || '(暂无)' }}</pre>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="clusterId" label="模板ID" width="90" />
        <el-table-column prop="templateText" label="模板内容" min-width="320" show-overflow-tooltip />
        <el-table-column prop="tokenCount" label="Token数" width="90" />
        <el-table-column label="级别" width="90">
          <template #default="{ row }">
            <el-tag :type="levelType(row.level)">{{ row.level }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="service" label="服务" width="140" show-overflow-tooltip />
        <el-table-column prop="totalCount" label="累计" width="90" />
        <el-table-column prop="lastWindowCount" label="近窗口" width="90" />
        <el-table-column prop="firstSeen" label="首次出现" width="170" />
        <el-table-column prop="lastSeen" label="最近出现" width="170" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="380" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="onTrend(row)">趋势</el-button>
            <el-button link type="info" @click="onSamples(row)">样本</el-button>
            <el-button link type="warning" @click="onExplain(row)">AI 解读</el-button>
            <template v-if="hasPerm('log:template:update')">
              <el-button v-if="row.status !== 1" link type="success" @click="onSetStatus(row, 1)">关注</el-button>
              <el-button v-else link type="success" @click="onSetStatus(row, 0)">取消关注</el-button>
              <el-button v-if="row.status !== 2" link type="danger" @click="onSetStatus(row, 2)">忽略</el-button>
              <el-button v-else link type="danger" @click="onSetStatus(row, 0)">取消忽略</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.current"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        style="margin-top: 12px; justify-content: flex-end"
        @change="load"
      />
    </el-card>

    <!-- 趋势 -->
    <el-dialog v-model="trendVisible" :title="`趋势 · 模板 #${trendRow?.clusterId ?? ''}`" width="880px" destroy-on-close>
      <div style="margin-bottom: 12px">
        <el-radio-group v-model="trendHours" @change="loadTrend">
          <el-radio-button :value="24">24 小时</el-radio-button>
          <el-radio-button :value="6">6 小时</el-radio-button>
          <el-radio-button :value="1">1 小时</el-radio-button>
        </el-radio-group>
      </div>
      <BaseChart v-if="trendVisible" :option="trendOption" height="380px" />
    </el-dialog>

    <!-- 样本 -->
    <el-dialog v-model="samplesVisible" :title="`样本 · 模板 #${samplesRow?.clusterId ?? ''}`" width="900px" destroy-on-close>
      <el-alert :title="`共 ${samplesTotal} 条样本`" type="info" :closable="false" style="margin-bottom: 12px" />
      <el-table :data="samplesRows" border stripe v-loading="samplesLoading" max-height="480">
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

    <!-- AI 解读 -->
    <el-dialog v-model="explainVisible" title="AI 解读" width="760px" destroy-on-close>
      <div v-loading="explainLoading">
        <el-alert type="info" :closable="false" style="margin-bottom: 12px">
          <template #title>解读结果（数据来源：log_analysis_record）</template>
        </el-alert>
        <el-descriptions v-if="explain" :column="1" border>
          <el-descriptions-item label="摘要">
            <pre class="explain-box">{{ explain.summary || '-' }}</pre>
          </el-descriptions-item>
          <el-descriptions-item label="可能原因">
            <pre class="explain-box">{{ explain.likelyCause || '-' }}</pre>
          </el-descriptions-item>
          <el-descriptions-item label="建议">
            <pre class="explain-box">{{ explain.suggestion || '-' }}</pre>
          </el-descriptions-item>
          <el-descriptions-item label="置信度">
            <el-progress
              v-if="typeof explain.confidence === 'number'"
              :percentage="Math.round((explain.confidence <= 1 ? explain.confidence * 100 : explain.confidence))"
              :stroke-width="14"
            />
            <span v-else>{{ explain.confidence ?? '-' }}</span>
          </el-descriptions-item>
        </el-descriptions>
        <el-empty v-else-if="!explainLoading" description="暂无解读内容" :image-size="60" />
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getTemplatePage, updateTemplateStatus,
  getTemplateTrend, getTemplateSamples, explainTemplate
} from '../../api/log'
import { useUserStore } from '../../store/user'
import BaseChart from '../../components/BaseChart.vue'

const userStore = useUserStore()
const hasPerm = code => userStore.hasPerm(code)

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const serviceOptions = ref([])

const query = reactive({
  current: 1,
  size: 10,
  status: null,
  keyword: '',
  service: '',
  datasourceId: 1,
  indexConfigId: 1
})

function levelType(l) {
  if (l === 'ERROR' || l === 'FATAL') return 'danger'
  if (l === 'WARN') return 'warning'
  if (l === 'DEBUG' || l === 'TRACE') return 'info'
  return 'success'
}
function statusType(s) {
  if (s === 0) return 'success'
  if (s === 1) return 'warning'
  if (s === 2) return 'info'
  return 'info'
}
function statusLabel(s) {
  if (s === 0) return '正常'
  if (s === 1) return '关注'
  if (s === 2) return '忽略'
  return s
}

async function load() {
  loading.value = true
  try {
    const params = {
      current: query.current,
      size: query.size,
      datasourceId: query.datasourceId,
      indexConfigId: query.indexConfigId
    }
    if (query.status !== null && query.status !== '' && query.status !== undefined) params.status = query.status
    if (query.keyword) params.keyword = query.keyword
    if (query.service) params.service = query.service
    const page = await getTemplatePage(params)
    rows.value = page.records || []
    total.value = page.total || 0
    // 顺手收集服务候选
    const services = new Set(serviceOptions.value)
    rows.value.forEach(r => { if (r.service) services.add(r.service) })
    serviceOptions.value = [...services]
  } finally {
    loading.value = false
  }
}

async function onSetStatus(row, status) {
  await updateTemplateStatus(row.id ?? row.clusterId, status)
  ElMessage.success('已更新状态')
  load()
}

// ============ 趋势 ============
const trendVisible = ref(false)
const trendRow = ref(null)
const trendHours = ref(24)
const trendOption = ref({})

async function onTrend(row) {
  trendRow.value = row
  trendHours.value = 24
  trendVisible.value = true
  await loadTrend()
}

async function loadTrend() {
  if (!trendRow.value) return
  const id = trendRow.value.id ?? trendRow.value.clusterId
  const data = await getTemplateTrend(id, { hours: trendHours.value })
  const times = (data && data.times) || []
  const counts = (data && data.counts) || []
  trendOption.value = {
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 20, top: 30, bottom: 30 },
    xAxis: { type: 'category', data: times },
    yAxis: { type: 'value' },
    series: [{
      name: '命中数',
      type: 'line',
      smooth: true,
      showSymbol: false,
      areaStyle: { opacity: 0.2 },
      data: times.map((t, i) => [t, counts[i]])
    }]
  }
}

// ============ 样本 ============
const samplesVisible = ref(false)
const samplesLoading = ref(false)
const samplesRow = ref(null)
const samplesRows = ref([])
const samplesTotal = ref(0)

async function onSamples(row) {
  samplesRow.value = row
  samplesVisible.value = true
  samplesLoading.value = true
  samplesRows.value = []
  samplesTotal.value = 0
  try {
    const id = row.id ?? row.clusterId
    const data = await getTemplateSamples(id, { size: 5 })
    samplesRows.value = (data && data.samples) || []
    samplesTotal.value = (data && data.total) || samplesRows.value.length
  } finally {
    samplesLoading.value = false
  }
}

// ============ AI 解读 ============
const explainVisible = ref(false)
const explainLoading = ref(false)
const explain = ref(null)

async function onExplain(row) {
  explainVisible.value = true
  explainLoading.value = true
  explain.value = null
  try {
    const id = row.id ?? row.clusterId
    explain.value = await explainTemplate(id)
  } finally {
    explainLoading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.header-bar { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px }
.sample-box {
  background: #f5f7fa;
  padding: 8px 12px;
  border-radius: 4px;
  font-size: 12px;
  max-height: 160px;
  overflow: auto;
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
}
.explain-box {
  background: #f5f7fa;
  padding: 8px 12px;
  border-radius: 4px;
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 13px;
}
</style>
