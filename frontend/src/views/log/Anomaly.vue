<template>
  <div>
    <el-card shadow="hover">
      <template #header>
        <div class="header-bar">
          <span>日志异常</span>
          <div>
            <el-select v-model="query.status" placeholder="状态" clearable style="width: 140px; margin-right: 8px">
              <el-option label="待处理" value="pending" />
              <el-option label="处理中" value="processing" />
              <el-option label="已解决" value="resolved" />
              <el-option label="误报" value="false_positive" />
            </el-select>
            <el-select v-model="query.anomalyType" placeholder="异常类型" clearable style="width: 160px; margin-right: 8px">
              <el-option label="新模板" value="new_template" />
              <el-option label="稀有模板" value="rare_template" />
              <el-option label="量突增" value="spike" />
              <el-option label="错误率异常" value="error_rate" />
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
        <el-table-column label="类型" width="130">
          <template #default="{ row }">
            <el-tag :type="anomalyTypeTag(row.anomalyType)">{{ anomalyTypeLabel(row.anomalyType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
        <el-table-column prop="description" label="描述" min-width="220" show-overflow-tooltip />
        <el-table-column prop="triggerValue" label="触发值" width="100" />
        <el-table-column prop="baselineValue" label="基线值" width="100">
          <template #default="{ row }">{{ row.baselineValue ?? '-' }}</template>
        </el-table-column>
        <el-table-column prop="count" label="次数" width="80" />
        <el-table-column prop="firstTime" label="首次时间" width="170" />
        <el-table-column prop="lastTime" label="最近时间" width="170" />
        <el-table-column label="操作" width="320" fixed="right">
          <template #default="{ row }">
            <template v-if="hasPerm('log:anomaly:handle')">
              <el-button v-if="row.status === 'pending'" link type="primary" @click="onClaim(row)">认领</el-button>
              <el-button v-if="row.status === 'pending' || row.status === 'processing'" link type="success" @click="onResolve(row)">解决</el-button>
              <el-button v-if="row.status === 'pending' || row.status === 'processing'" link type="warning" @click="onFalsePositive(row)">标记误报</el-button>
            </template>
            <el-button link type="info" @click="onExplain(row)">AI 解读</el-button>
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

    <!-- AI 解读 -->
    <el-dialog v-model="explainVisible" title="AI 解读" width="760px" destroy-on-close>
      <div v-loading="explainLoading">
        <el-alert type="info" :closable="false" style="margin-bottom: 12px">
          <template #title>异常解读（数据来源：log_analysis_record）</template>
        </el-alert>
        <el-descriptions v-if="explain" :column="1" border>
          <el-descriptions-item label="摘要">
            <pre class="explain-box">{{ explain.summary || '-' }}</pre>
          </el-descriptions-item>
          <el-descriptions-item label="可能原因">
            <pre class="explain-box">{{ explain.likelyCause || '-' }}</pre>
          </el-descriptions-item>
          <el-descriptions-item label="处置建议">
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
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getAnomalyPage, claimAnomaly, resolveAnomaly, falsePositiveAnomaly, explainAnomaly
} from '../../api/log'
import { useUserStore } from '../../store/user'

const userStore = useUserStore()
const hasPerm = code => userStore.hasPerm(code)

const rows = ref([])
const total = ref(0)
const loading = ref(false)

const query = reactive({ current: 1, size: 10, status: '', anomalyType: '', datasourceId: 1 })

function levelType(l) {
  if (l === 'ERROR' || l === 'FATAL' || l === 'CRITICAL') return 'danger'
  if (l === 'WARN') return 'warning'
  return 'info'
}
function anomalyTypeTag(t) {
  switch (t) {
    case 'new_template': return 'primary'
    case 'rare_template': return 'warning'
    case 'spike': return 'danger'
    case 'error_rate': return 'danger'
    default: return 'info'
  }
}
function anomalyTypeLabel(t) {
  switch (t) {
    case 'new_template': return '新模板'
    case 'rare_template': return '稀有模板'
    case 'spike': return '量突增'
    case 'error_rate': return '错误率'
    default: return t
  }
}
function statusType(s) {
  switch (s) {
    case 'pending': return 'info'
    case 'processing': return 'warning'
    case 'resolved': return 'success'
    case 'false_positive': return 'info'
    default: return 'info'
  }
}
function statusLabel(s) {
  switch (s) {
    case 'pending': return '待处理'
    case 'processing': return '处理中'
    case 'resolved': return '已解决'
    case 'false_positive': return '误报'
    default: return s
  }
}

async function load() {
  loading.value = true
  try {
    const params = { current: query.current, size: query.size }
    if (query.status) params.status = query.status
    if (query.anomalyType) params.anomalyType = query.anomalyType
    if (query.datasourceId) params.datasourceId = query.datasourceId
    const page = await getAnomalyPage(params)
    rows.value = page.records || []
    total.value = page.total || 0
  } finally {
    loading.value = false
  }
}

async function onClaim(row) {
  await claimAnomaly(row.id)
  ElMessage.success('认领成功')
  load()
}
async function onResolve(row) {
  const { value } = await ElMessageBox.prompt('请输入解决备注', '解决异常', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    inputPlaceholder: '可选'
  })
  await resolveAnomaly(row.id, { remark: value || '' })
  ElMessage.success('已标记解决')
  load()
}
async function onFalsePositive(row) {
  await ElMessageBox.confirm('确认将该异常标记为误报？', '提示', { type: 'warning' })
  await falsePositiveAnomaly(row.id)
  ElMessage.success('已标记误报')
  load()
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
    explain.value = await explainAnomaly(row.id)
  } finally {
    explainLoading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.header-bar { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px }
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
