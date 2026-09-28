<template>
  <div>
    <el-card shadow="hover">
      <template #header>
        <div class="header-bar">
          <span>事件管理</span>
          <div>
            <el-select v-model="query.status" placeholder="状态" clearable style="width: 140px; margin-right: 8px">
              <el-option label="打开" value="open" />
              <el-option label="处理中" value="processing" />
              <el-option label="已解决" value="resolved" />
              <el-option label="已关闭" value="closed" />
            </el-select>
            <el-input
              v-model="query.keyword"
              placeholder="事件号 / 标题关键字"
              clearable
              style="width: 220px; margin-right: 8px"
              @keyup.enter="load"
            />
            <el-button type="primary" @click="load">搜索</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" border stripe v-loading="loading">
        <el-table-column prop="incidentNo" label="事件号" width="180" />
        <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
        <el-table-column label="级别" width="100">
          <template #default="{ row }">
            <el-tag :type="levelType(row.level)">{{ row.level }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="alertCount" label="告警数" width="90" />
        <el-table-column prop="startTime" label="开始时间" width="170" />
        <el-table-column prop="primaryTargetId" label="主对象" width="90" />
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button v-if="row.status !== 'resolved' && row.status !== 'closed'" link type="success" @click="onResolve(row)">标记解决</el-button>
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

    <!-- 详情 -->
    <el-dialog v-model="detailVisible" title="事件详情" width="800px" destroy-on-close>
      <div v-loading="detailLoading">
        <el-descriptions :column="2" border v-if="detail">
          <el-descriptions-item label="事件号">{{ detail.incidentNo }}</el-descriptions-item>
          <el-descriptions-item label="标题">{{ detail.title }}</el-descriptions-item>
          <el-descriptions-item label="级别">
            <el-tag :type="levelType(detail.level)">{{ detail.level }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusType(detail.status)">{{ statusLabel(detail.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="告警数">{{ detail.alertCount }}</el-descriptions-item>
          <el-descriptions-item label="开始时间">{{ detail.startTime }}</el-descriptions-item>
          <el-descriptions-item label="LLM 根因" :span="2" v-if="detail.llmRootCause">
            <pre class="llm-box">{{ detail.llmRootCause }}</pre>
          </el-descriptions-item>
        </el-descriptions>

        <div style="margin: 16px 0 8px; font-weight: bold">时间线</div>
        <el-timeline v-if="detail && (detail.timeline || []).length">
          <el-timeline-item
            v-for="(item, idx) in detail.timeline"
            :key="idx"
            :timestamp="item.eventTime"
          >
            <el-tag size="small" style="margin-right: 6px">{{ item.eventType }}</el-tag>
            {{ item.description }}
            <span v-if="item.operator" style="color: #909399; margin-left: 6px">— {{ item.operator }}</span>
          </el-timeline-item>
        </el-timeline>
        <el-empty v-else description="暂无时间线" :image-size="60" />

        <div style="margin: 16px 0 8px; font-weight: bold">关联告警</div>
        <el-table :data="(detail && detail.alerts) || []" border stripe size="small" max-height="240">
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column label="级别" width="90">
            <template #default="{ row }">
              <el-tag :type="levelType(row.level)">{{ row.level }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="metricKey" label="指标" width="140" />
          <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
        </el-table>

        <div style="margin: 16px 0 8px; font-weight: bold">添加事件</div>
        <div class="add-note">
          <el-input v-model="noteInput" placeholder="填写事件说明（eventType=comment）" />
          <el-button type="primary" :loading="adding" @click="onAddNote">添加事件</el-button>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getIncidentPage, getIncident, resolveIncident, addTimeline } from '../../api/incident'

const rows = ref([])
const total = ref(0)
const loading = ref(false)

const query = reactive({ current: 1, size: 10, status: '', keyword: '' })

function levelType(l) {
  if (l === 'CRITICAL' || l === 'P1') return 'danger'
  if (l === 'WARN' || l === 'P2') return 'warning'
  return 'info'
}
function statusType(s) {
  switch (s) {
    case 'open': return 'danger'
    case 'processing': return 'warning'
    case 'resolved': return 'success'
    case 'closed': return 'info'
    default: return 'info'
  }
}
function statusLabel(s) {
  switch (s) {
    case 'open': return '打开'
    case 'processing': return '处理中'
    case 'resolved': return '已解决'
    case 'closed': return '已关闭'
    default: return s
  }
}

async function load() {
  loading.value = true
  try {
    const params = { current: query.current, size: query.size }
    if (query.status) params.status = query.status
    if (query.keyword) params.keyword = query.keyword
    const page = await getIncidentPage(params)
    rows.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
  }
}

async function onResolve(row) {
  const { value } = await ElMessageBox.prompt(`标记事件 ${row.incidentNo} 为已解决`, '解决事件', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    inputPlaceholder: '解决备注（可选）'
  })
  await resolveIncident(row.id, { note: value || '' })
  ElMessage.success('已标记解决')
  load()
  if (detailVisible.value && detail.value && detail.value.id === row.id) {
    openDetail(row)
  }
}

// 详情
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
const noteInput = ref('')
const adding = ref(false)

async function openDetail(row) {
  detailVisible.value = true
  detailLoading.value = true
  noteInput.value = ''
  try {
    detail.value = await getIncident(row.id)
  } finally {
    detailLoading.value = false
  }
}

async function onAddNote() {
  if (!noteInput.value) {
    ElMessage.warning('请输入事件说明')
    return
  }
  adding.value = true
  try {
    await addTimeline(detail.value.id, { event_type: 'comment', description: noteInput.value })
    ElMessage.success('已添加')
    noteInput.value = ''
    detail.value = await getIncident(detail.value.id)
  } finally {
    adding.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.header-bar { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px }
.llm-box {
  background: #f5f7fa;
  padding: 8px 12px;
  border-radius: 4px;
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
}
.add-note { display: flex; gap: 8px }
.add-note .el-input { flex: 1 }
</style>
