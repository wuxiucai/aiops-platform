<template>
  <div>
    <el-card shadow="hover">
      <div class="page-head">
        <h2>采集任务管理</h2>
        <el-button type="primary" size="small" @click="openForm(null)" v-if="hasPerm('monitor:collect:update')" icon="Plus">新增采集任务</el-button>
      </div>

      <el-table :data="rows" border stripe v-loading="loading" empty-text="暂无数据">
        <el-table-column prop="id" label="ID" width="60"/>
        <el-table-column label="目标" min-width="160">
          <template #default="{ row }">{{ targetName(row.targetId) }}</template>
        </el-table-column>
        <el-table-column prop="intervalSec" label="间隔（s)" width="90"/>
        <el-table-column label="指标 Keys" min-width="180">
          <template #default="{ row }">
            <el-tag v-for="k in JSON.parse(row.metricKeys || '[]')" :key="k" size="small" style="margin:2px">{{ k }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastRunTime" label="上次运行" width="150"/>
        <el-table-column prop="lastCostMs" label="上次耗时" width="90"/>
        <el-table-column prop="failCount" label="失败次数" width="90">
          <template #default="{ row }">
            <el-tag :type="row.failCount > 0 ? 'danger' : 'success'" size="small">{{ row.failCount }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-switch v-model="row.status" :active-value="1" :inactive-value="0" @change="toggle(row)" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="runNow(row)">立即执行</el-button>
            <el-button link type="primary" size="small" @click="openForm(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="onDelete(row)" v-if="hasPerm('monitor:collect:update')">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建/编辑 dialog -->
    <el-dialog v-model="dialog" :title="form.id ? '编辑' : '新增' + '采集任务'" width="600px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="目标" required>
          <el-select v-model="form.targetId" style="width: 100%">
            <el-option v-for="t in targets" :key="t.id" :value="t.id" :label="`${t.name} (${t.ip})`"/>
          </el-select>
        </el-form-item>
        <el-form-item label="指标 Keys" required>
          <el-select v-model="form.metricKeys" multiple style="width: 100%">
            <el-option v-for="m in metricDefs" :key="m.metricKey" :value="m.metricKey" :label="`${m.metricKey} — ${m.metricName || m.unit || ''}`"/>
          </el-select>
        </el-form-item>
        <el-form-item label="采集间隔（s)" required>
          <el-input-number v-model="form.intervalSec" :min="5" :max="3600" :step="5"/>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog=false">取消</el-button>
        <el-button type="primary" @click="save" :loading="saving">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../../utils/request'
import { useUserStore } from '../../store/user'

const userStore = useUserStore()
const hasPerm = (code) => userStore.hasPerm(code)

const rows = ref([])
const targets = ref([])
const metricDefs = ref([])
const loading = ref(false)
const saving = ref(false)
const dialog = ref(false)
const form = ref(defaultForm())

function defaultForm () {
  return { id: null, targetId: null, metricKeys: [], intervalSec: 30, status: 1 }
}

function targetName (id) {
  return targets.value.find(t => t.id === id)?.name || `target#${id}`
}

async function load () {
  loading.value = true
  try {
    const r = await request.get('/api/monitor/collect/page', { params: { current: 1, size: 100 } })
    rows.value = (r.data?.records) || []
  } finally { loading.value = false }
}

async function loadTargets () {
  const r = await request.get('/api/monitor/target/page', { params: { current: 1, size: 100 } })
  targets.value = r.data?.records || []
}

async function loadMetricDefs () {
  const r = await request.get('/api/monitor/metric/definitions')
  metricDefs.value = r.data || []
}

function openForm (row) {
  if (row) {
    form.value = { ...row, metricKeys: JSON.parse(row.metricKeys || '[]') }
  } else {
    form.value = defaultForm()
    if (targets.value.length > 0) form.value.targetId = targets.value[0].id
  }
  dialog.value = true
}

async function save () {
  if (!form.value.targetId || form.value.metricKeys.length === 0) {
    ElMessage.warning('目标与指标至少选一个')
    return
  }
  saving.value = true
  try {
    const body = { ...form.value, metricKeys: JSON.stringify(form.value.metricKeys) }
    let r
    if (form.value.id) r = await request.put('/api/monitor/collect', body)
    else              r = await request.post('/api/monitor/collect', body)
    if (r.code === 200) {
      ElMessage.success('保存成功')
      dialog.value = false
      load()
    } else ElMessage.error(r.msg || '保存失败')
  } finally { saving.value = false }
}

async function toggle (row) {
  const body = { ...row, metricKeys: row.metricKeys }
  const r = await request.put('/api/monitor/collect', body)
  if (r.code === 200) ElMessage.success(row.status === 1 ? '已启用' : '已停用')
  else { ElMessage.error(r.msg || '更新失败'); load() }
}

async function runNow (row) {
  const r = await request.post(`/api/monitor/collect/task/${row.id}/run`)
  if (r.code === 200) {
    ElMessage.success(`正在执行 (约 ${row.intervalSec}s)...`)
    setTimeout(load, 2000)
  } else ElMessage.error(r.msg || '执行失败')
}

async function onDelete (row) {
  try {
    await ElMessageBox.confirm(
      `确定删除采集任务 #${row.id} (${targetName(row.targetId)})？删除后采集会停止。`,
      '删除',
      { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' }
    )
  } catch { return }
  const r = await request.delete(`/api/monitor/collect/${row.id}`)
  if (r.code === 200) { ElMessage.success('已删除'); load() }
  else ElMessage.error(r.msg || '删除失败')
}

onMounted(() => {
  loadTargets()
  loadMetricDefs()
  load()
})
</script>

<style scoped>
.page-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-head h2 { margin: 0; font-size: 20px; }
</style>
