<template>
  <div>
    <el-card shadow="hover">
      <template #header>
        <div class="header-bar">
          <span>告警记录</span>
          <div>
            <el-input
              v-model="query.title"
              placeholder="标题关键字"
              clearable
              style="width: 200px; margin-right: 8px"
              @keyup.enter="load"
            />
            <el-select v-model="query.level" placeholder="级别" clearable style="width: 120px; margin-right: 8px">
              <el-option label="INFO" value="INFO" />
              <el-option label="WARN" value="WARN" />
              <el-option label="CRITICAL" value="CRITICAL" />
            </el-select>
            <el-select v-model="query.status" placeholder="状态" clearable style="width: 140px; margin-right: 8px">
              <el-option label="待处理" value="pending" />
              <el-option label="处理中" value="processing" />
              <el-option label="已解决" value="resolved" />
              <el-option label="已关闭" value="closed" />
              <el-option label="误报" value="false_positive" />
            </el-select>
            <el-button type="primary" @click="load">查询</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" border stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="级别" width="90">
          <template #default="{ row }">
            <el-tag :type="levelType(row.level)">{{ row.level }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="metricKey" label="指标" width="140" />
        <el-table-column prop="triggerValue" label="触发值" width="100" />
        <el-table-column prop="thresholdValue" label="阈值" width="100" />
        <el-table-column prop="triggerCount" label="次数" width="80" />
        <el-table-column prop="firstTriggerTime" label="首次触发时间" width="170" />
        <el-table-column prop="claim" label="认领人" width="100">
          <template #default="{ row }">{{ row.claim || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="320" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'pending'" link type="primary" @click="onClaim(row)">认领</el-button>
            <el-button v-if="row.status === 'pending' || row.status === 'processing'" link type="success" @click="onResolve(row)">解决</el-button>
            <el-button v-if="row.status === 'pending' || row.status === 'processing'" link type="danger" @click="onClose(row)">关闭</el-button>
            <el-button v-if="row.status === 'pending' || row.status === 'processing'" link type="warning" @click="onFalsePositive(row)">标记误报</el-button>
            <el-button link type="info" @click="onRelatedLogs(row)">关联日志</el-button>
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

    <!-- 关联日志 -->
    <el-dialog v-model="logsVisible" title="关联日志" width="900px" destroy-on-close>
      <div style="margin-bottom: 8px">
        <el-alert :title="`共 ${logsTotal} 条日志`" type="info" :closable="false" />
      </div>
      <div v-if="logsDsl" style="margin-bottom: 12px">
        <div style="font-weight: bold; margin-bottom: 6px">DSL 查询（供论文截图）</div>
        <pre class="dsl-box">{{ logsDsl }}</pre>
      </div>
      <el-table :data="logsRows" border stripe max-height="420" v-loading="logsLoading">
        <el-table-column prop="time" label="时间" width="170" />
        <el-table-column prop="level" label="级别" width="90">
          <template #default="{ row }">
            <el-tag :type="logLevelType(row.level)">{{ row.level }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="service" label="服务" width="140" />
        <el-table-column prop="message" label="消息" min-width="240" show-overflow-tooltip />
        <el-table-column prop="traceId" label="TraceId" width="200" show-overflow-tooltip />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getRecordPage, claimRecord, resolveRecord, closeRecord,
  falsePositiveRecord, relatedLogs
} from '../../api/alert'

const rows = ref([])
const total = ref(0)
const loading = ref(false)

const query = reactive({ current: 1, size: 10, title: '', level: '', status: '' })

function levelType(l) {
  if (l === 'CRITICAL') return 'danger'
  if (l === 'WARN') return 'warning'
  return 'info'
}
function statusType(s) {
  switch (s) {
    case 'pending': return 'info'
    case 'processing': return 'warning'
    case 'resolved': return 'success'
    case 'closed': return 'danger'
    case 'false_positive': return 'info'
    default: return 'info'
  }
}
function statusLabel(s) {
  switch (s) {
    case 'pending': return '待处理'
    case 'processing': return '处理中'
    case 'resolved': return '已解决'
    case 'closed': return '已关闭'
    case 'false_positive': return '误报'
    default: return s
  }
}
function logLevelType(l) {
  if (l === 'ERROR' || l === 'FATAL') return 'danger'
  if (l === 'WARN') return 'warning'
  if (l === 'DEBUG' || l === 'TRACE') return 'info'
  return 'success'
}

async function load() {
  loading.value = true
  try {
    const params = { current: query.current, size: query.size }
    if (query.title) params.title = query.title
    if (query.level) params.level = query.level
    if (query.status) params.status = query.status
    const page = await getRecordPage(params)
    rows.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
  }
}

async function onClaim(row) {
  await claimRecord(row.id, {})
  ElMessage.success('认领成功')
  load()
}
async function onResolve(row) {
  const { value } = await ElMessageBox.prompt('请输入解决备注', '解决告警', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    inputPlaceholder: '可选'
  })
  await resolveRecord(row.id, { note: value || '' })
  ElMessage.success('已标记解决')
  load()
}
async function onClose(row) {
  const { value } = await ElMessageBox.prompt('请输入关闭备注', '关闭告警', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    inputPlaceholder: '可选'
  })
  await closeRecord(row.id, { note: value || '' })
  ElMessage.success('已关闭')
  load()
}
async function onFalsePositive(row) {
  const { value } = await ElMessageBox.prompt('请输入误报原因', '标记误报', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    inputPlaceholder: '可选'
  })
  await falsePositiveRecord(row.id, { note: value || '' })
  ElMessage.success('已标记误报')
  load()
}

// 关联日志
const logsVisible = ref(false)
const logsLoading = ref(false)
const logsRows = ref([])
const logsTotal = ref(0)
const logsDsl = ref('')

async function onRelatedLogs(row) {
  logsVisible.value = true
  logsLoading.value = true
  logsRows.value = []
  logsTotal.value = 0
  logsDsl.value = ''
  try {
    const data = await relatedLogs(row.id)
    logsRows.value = (data && data.records) || []
    logsTotal.value = (data && data.total) || logsRows.value.length
    logsDsl.value = (data && data.dsl) || ''
  } finally {
    logsLoading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.header-bar { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px }
.dsl-box {
  background: #f5f7fa;
  padding: 8px 12px;
  border-radius: 4px;
  font-size: 12px;
  max-height: 200px;
  overflow: auto;
  margin: 0;
}
</style>
