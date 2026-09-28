<template>
  <div>
    <el-card shadow="hover">
      <template #header>
        <div class="header-bar">
          <span>静默规则</span>
          <div>
            <el-input
              v-model="query.name"
              placeholder="名称关键字"
              clearable
              style="width: 200px; margin-right: 8px"
              @keyup.enter="load"
            />
            <el-button type="primary" @click="load">查询</el-button>
            <el-button v-if="hasPerm('alert:silence:update')" type="success" @click="openDialog()">新增静默</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" border stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="name" label="名称" min-width="160" show-overflow-tooltip />
        <el-table-column label="监控对象" width="120">
          <template #default="{ row }">{{ row.targetId ?? '不限' }}</template>
        </el-table-column>
        <el-table-column label="规则" width="120">
          <template #default="{ row }">{{ row.ruleId ?? '不限' }}</template>
        </el-table-column>
        <el-table-column prop="startTime" label="开始时间" width="170" />
        <el-table-column prop="endTime" label="结束时间" width="170" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '生效中' : '已停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createBy" label="创建人" width="100" />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button v-if="hasPerm('alert:silence:update')" link type="danger" @click="onDelete(row)">删除</el-button>
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

    <el-dialog v-model="dialogVisible" title="新增静默" width="560px" destroy-on-close>
      <el-form :model="form" :rules="rules" ref="formRef" label-width="100px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="监控对象">
          <el-select v-model="form.targetId" clearable placeholder="留空 = 不限" style="width: 100%">
            <el-option v-for="t in targets" :key="t.id" :label="t.name" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="告警规则">
          <el-select v-model="form.ruleId" clearable placeholder="留空 = 不限" style="width: 100%">
            <el-option v-for="r in rulesOption" :key="r.id" :label="r.name" :value="r.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="开始时间" prop="startTime">
          <el-date-picker
            v-model="form.startTime"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="选择开始时间"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="结束时间" prop="endTime">
          <el-date-picker
            v-model="form.endTime"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="选择结束时间"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="原因">
          <el-input v-model="form.reason" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getSilencePage, addSilence, deleteSilence } from '../../api/alert'
import { getRulePage } from '../../api/alert'
import { listTarget } from '../../api/monitor'
import { useUserStore } from '../../store/user'

const userStore = useUserStore()
const hasPerm = code => userStore.hasPerm(code)

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const formRef = ref()
const targets = ref([])
const rulesOption = ref([])

const query = reactive({ current: 1, size: 10, name: '' })
const defaultForm = () => ({
  name: '',
  targetId: null,
  ruleId: null,
  startTime: '',
  endTime: '',
  reason: '',
  status: 1
})
const form = reactive(defaultForm())

const rules = {
  name: [{ required: true, message: '名称不能为空', trigger: 'blur' }],
  startTime: [{ required: true, message: '请选择开始时间', trigger: 'change' }],
  endTime: [{ required: true, message: '请选择结束时间', trigger: 'change' }]
}

async function load() {
  loading.value = true
  try {
    const params = { current: query.current, size: query.size }
    if (query.name) params.name = query.name
    const page = await getSilencePage(params)
    rows.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
  }
}

async function loadOptions() {
  if (targets.value.length === 0) {
    targets.value = await listTarget()
  }
  if (rulesOption.value.length === 0) {
    const page = await getRulePage({ current: 1, size: 100 })
    rulesOption.value = page.records || []
  }
}

async function openDialog() {
  Object.assign(form, defaultForm())
  await loadOptions()
  dialogVisible.value = true
}

async function onSave() {
  await formRef.value.validate()
  saving.value = true
  try {
    await addSilence(form)
    ElMessage.success('新增成功')
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function onDelete(row) {
  await ElMessageBox.confirm(`确认删除静默「${row.name}」？`, '提示', { type: 'warning' })
  await deleteSilence(row.id)
  ElMessage.success('删除成功')
  load()
}

onMounted(load)
</script>

<style scoped>
.header-bar { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px }
</style>
