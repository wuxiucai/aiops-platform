<template>
  <el-card shadow="hover">
    <div class="header-bar">
      <div class="left">
        <el-select v-model="query.enabled" placeholder="全部状态" clearable style="width: 140px" @change="load">
          <el-option label="已启用" :value="1" />
          <el-option label="已停用" :value="0" />
        </el-select>
        <el-button type="primary" @click="load">搜索</el-button>
        <el-button @click="onReset">重置</el-button>
      </div>
      <el-button v-if="hasPerm('log:rule:update')" type="success" @click="openDialog()">新增规则</el-button>
    </div>

    <el-table :data="rows" border stripe v-loading="loading" style="width: 100%">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="规则名" min-width="180" />
      <el-table-column label="类型" width="130">
        <template #default="{ row }">
          <el-tag :type="typeTag(row.ruleType)">{{ typeLabel(row.ruleType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="参数" min-width="220">
        <template #default="{ row }">
          <pre class="param-pre">{{ row.params }}</pre>
        </template>
      </el-table-column>
      <el-table-column prop="level" label="级别" width="100">
        <template #default="{ row }">
          <el-tag :type="row.level === 'CRITICAL' ? 'danger' : 'warning'">{{ row.level }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="80">
        <template #default="{ row }">
          <el-switch v-model="row.enabled" :active-value="1" :inactive-value="0"
                     :disabled="!hasPerm('log:rule:update')"
                     @change="v => onToggle(row, v)" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button v-if="hasPerm('log:rule:update')" link type="primary" @click="openDialog(row)">编辑</el-button>
          <el-button v-if="hasPerm('log:rule:update')" link type="danger" @click="onDelete(row)">删除</el-button>
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
      @current-change="load"
      @size-change="load" />

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑规则' : '新增规则'" width="600px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="规则名" prop="name">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="规则类型" prop="ruleType">
          <el-select v-model="form.ruleType" @change="onTypeChange" style="width: 100%">
            <el-option label="新模板告警" value="new_template" />
            <el-option label="稀有模板告警" value="rare_template" />
            <el-option label="模板爆量" value="spike" />
            <el-option label="错误率飙升" value="error_rate" />
          </el-select>
        </el-form-item>
        <el-form-item label="params(JSON)" prop="params">
          <el-input v-model="form.params" type="textarea" :rows="4" :placeholder="paramsPlaceholder" />
        </el-form-item>
        <el-form-item label="级别">
          <el-select v-model="form.level" style="width: 100%">
            <el-option label="INFO" value="INFO" />
            <el-option label="WARN" value="WARN" />
            <el-option label="CRITICAL" value="CRITICAL" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getRulePage, addRule, updateRule, deleteRule, toggleRule } from '../../api/log'
import { useUserStore } from '../../store/user'

const userStore = useUserStore()
const hasPerm = code => userStore.hasPerm(code)

const PLACEHOLDERS = {
  new_template: '{"threshold":1}',
  rare_template: '{"window_min":10,"max_count":3}',
  spike: '{"window_min":10,"spike_ratio":3.0}',
  error_rate: '{"window_min":10,"error_ratio":0.3}'
}

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const formRef = ref(null)

const query = reactive({ current: 1, size: 20, enabled: null })
const form = reactive({ id: null, name: '', ruleType: 'new_template', params: PLACEHOLDERS.new_template, level: 'WARN', enabled: 1 })

const rules = {
  name: [{ required: true, message: '规则名必填', trigger: 'blur' }],
  ruleType: [{ required: true, message: '选择类型', trigger: 'change' }],
  params: [{
    validator: (r, v, cb) => {
      if (!v) return cb()
      try { JSON.parse(v); cb() } catch { cb(new Error('params 不是合法 JSON')) }
    },
    trigger: 'blur'
  }]
}

const paramsPlaceholder = computed(() => PLACEHOLDERS[form.ruleType] || '')

function typeLabel(t) {
  return { new_template: '新模板', rare_template: '稀有模板', spike: '爆量', error_rate: '错误率' }[t] || t
}
function typeTag(t) {
  return { new_template: 'primary', rare_template: 'warning', spike: 'danger', error_rate: 'danger' }[t] || 'info'
}

async function load() {
  loading.value = true
  try {
    const data = await getRulePage({ current: query.current, size: query.size, enabled: query.enabled })
    rows.value = data.records
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function onReset() {
  query.enabled = null
  query.current = 1
  load()
}

function openDialog(row) {
  if (row) Object.assign(form, { id: row.id, name: row.name, ruleType: row.ruleType, params: row.params || '', level: row.level || 'WARN', enabled: row.enabled })
  else Object.assign(form, { id: null, name: '', ruleType: 'new_template', params: PLACEHOLDERS.new_template, level: 'WARN', enabled: 1 })
  dialogVisible.value = true
}

function onTypeChange(t) {
  if (!form.params) form.params = PLACEHOLDERS[t]
}

async function onSave() {
  await formRef.value.validate()
  saving.value = true
  try {
    if (form.id) await updateRule(form)
    else await addRule(form)
    ElMessage.success('已保存')
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function onDelete(row) {
  await ElMessageBox.confirm(`删除规则 ${row.name}？`, '确认', { type: 'warning' })
  await deleteRule(row.id)
  ElMessage.success('已删除')
  load()
}

async function onToggle(row, v) {
  await toggleRule(row.id, v)
  ElMessage.success(v === 1 ? '已启用' : '已停用')
}

onMounted(load)
</script>

<style scoped>
.header-bar { display: flex; justify-content: space-between; margin-bottom: 12px; }
.header-bar .left { display: flex; gap: 8px; }
.param-pre { margin: 0; font-size: 12px; white-space: pre-wrap; word-break: break-all; }
</style>
