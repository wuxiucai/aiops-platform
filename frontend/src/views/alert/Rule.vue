<template>
  <div>
    <el-card shadow="hover">
      <template #header>
        <div class="header-bar">
          <span>告警规则</span>
          <div>
            <el-input
              v-model="query.name"
              placeholder="规则名称"
              clearable
              style="width: 180px; margin-right: 8px"
              @keyup.enter="load"
            />
            <el-select v-model="query.ruleType" placeholder="规则类型" clearable style="width: 130px; margin-right: 8px">
              <el-option label="静态阈值" value="static" />
              <el-option label="动态基线" value="baseline" />
            </el-select>
            <el-select v-model="query.enabled" placeholder="状态" clearable style="width: 110px; margin-right: 8px">
              <el-option label="启用" :value="1" />
              <el-option label="停用" :value="0" />
            </el-select>
            <el-button type="primary" @click="load">查询</el-button>
            <el-button v-if="hasPerm('alert:rule:update')" type="success" @click="openDialog()">新增规则</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" border stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="规则名称" min-width="160" show-overflow-tooltip />
        <el-table-column prop="targetId" label="对象ID" width="80" />
        <el-table-column label="类型" width="100">
          <template #default="{ row }">
            <el-tag :type="row.ruleType === 'baseline' ? 'success' : 'primary'">
              {{ row.ruleType === 'baseline' ? '基线' : '静态' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="条件" width="140">
          <template #default="{ row }">
            <span v-if="row.ruleType === 'static'">{{ row.operator }} {{ row.threshold }}</span>
            <span v-else>{{ row.operator }}（基线）</span>
          </template>
        </el-table-column>
        <el-table-column label="级别" width="90">
          <template #default="{ row }">
            <el-tag :type="levelType(row.level)">{{ row.level }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="durationSec" label="持续(秒)" width="90" />
        <el-table-column label="启用" width="80">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled === 1"
              :disabled="!hasPerm('alert:rule:update')"
              @change="onToggle(row, $event)"
            />
          </template>
        </el-table-column>
        <el-table-column prop="createBy" label="创建人" width="100" />
        <el-table-column label="操作" width="320" fixed="right">
          <template #default="{ row }">
            <el-button v-if="hasPerm('alert:rule:update')" link type="primary" @click="openDialog(row)">编辑</el-button>
            <el-button v-if="hasPerm('alert:rule:update')" link type="danger" @click="onDelete(row)">删除</el-button>
            <el-button v-if="row.ruleType === 'baseline'" link type="warning" @click="onTrain(row)">训练基线</el-button>
            <el-button link type="info" @click="onDryRun(row)">干跑</el-button>
            <el-button v-if="row.ruleType === 'baseline'" link type="success" @click="onBaseline(row)">基线图</el-button>
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

    <!-- 新增 / 编辑 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑规则' : '新增规则'" width="750px" destroy-on-close>
      <el-form :model="form" :rules="rules" ref="formRef" label-width="110px">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="规则名称" prop="name">
              <el-input v-model="form.name" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="监控对象" prop="targetId">
              <el-select v-model="form.targetId" style="width: 100%">
                <el-option v-for="t in targets" :key="t.id" :label="t.name" :value="t.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="指标" prop="metricKey">
              <el-select v-model="form.metricKey" style="width: 100%">
                <el-option v-for="k in metricKeys" :key="k" :label="k" :value="k" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="规则类型" prop="ruleType">
              <el-select v-model="form.ruleType" style="width: 100%">
                <el-option label="静态阈值" value="static" />
                <el-option label="动态基线" value="baseline" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="操作符" prop="operator">
              <el-select v-model="form.operator" style="width: 100%">
                <el-option label="> (gt)" value="gt" />
                <el-option label=">= (gte)" value="gte" />
                <el-option label="< (lt)" value="lt" />
                <el-option label="<= (lte)" value="lte" />
                <el-option label="越出区间 (outside)" value="outside" />
                <el-option label="区间内 (inside)" value="inside" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="阈值" :prop="form.ruleType === 'static' ? 'threshold' : ''">
              <el-input-number v-model="form.threshold" :step="1" style="width: 100%" :disabled="form.ruleType !== 'static'" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="级别" prop="level">
              <el-select v-model="form.level" style="width: 100%">
                <el-option label="INFO" value="INFO" />
                <el-option label="WARN" value="WARN" />
                <el-option label="CRITICAL" value="CRITICAL" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="持续时长(秒)">
              <el-input-number v-model="form.durationSec" :min="0" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="通知渠道">
              <el-input v-model="form.notifyChannels" placeholder='JSON，例：["inapp","email"]' />
            </el-form-item>
          </el-col>
          <el-col :span="24" v-if="form.ruleType === 'baseline'">
            <el-form-item label="基线配置">
              <el-input v-model="form.baselineConfig" placeholder='JSON，例：{"days":7,"k":3}' />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="启用">
              <el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 干跑结果 -->
    <el-dialog v-model="dryRunVisible" title="干跑结果" width="700px" destroy-on-close>
      <el-alert :title="`共命中 ${dryRunResult.total ?? 0} 个点`" type="info" :closable="false" style="margin-bottom: 12px" />
      <el-table :data="dryRunRows" border stripe max-height="420">
        <el-table-column prop="time" label="时间" width="200" />
        <el-table-column prop="value" label="值" />
      </el-table>
    </el-dialog>

    <!-- 基线图 -->
    <el-dialog v-model="baselineVisible" title="基线图" width="900px" destroy-on-close>
      <BaseChart v-if="baselineVisible" :option="baselineOption" height="480px" />
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getRulePage, addRule, updateRule, deleteRule, toggleRule,
  trainRule, dryRun, baselineChart
} from '../../api/alert'
import { listTarget } from '../../api/monitor'
import { useUserStore } from '../../store/user'
import BaseChart from '../../components/BaseChart.vue'

const userStore = useUserStore()
const hasPerm = code => userStore.hasPerm(code)

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const formRef = ref()
const targets = ref([])

const query = reactive({ current: 1, size: 10, name: '', ruleType: '', enabled: null })
const metricKeys = ['cpu.usage', 'mem.usage', 'jvm.heap.usage', 'app.rt.avg', 'net.conn.count']

const defaultForm = () => ({
  id: null,
  name: '',
  targetId: null,
  metricKey: 'cpu.usage',
  ruleType: 'static',
  operator: 'gt',
  threshold: 80,
  level: 'WARN',
  durationSec: 60,
  notifyChannels: '["inapp"]',
  baselineConfig: '{"days":7,"k":3}',
  enabled: 1
})
const form = reactive(defaultForm())

const rules = {
  name: [{ required: true, message: '名称不能为空', trigger: 'blur' }],
  targetId: [{ required: true, message: '请选择监控对象', trigger: 'change' }],
  metricKey: [{ required: true, message: '请选择指标', trigger: 'change' }],
  ruleType: [{ required: true, message: '请选择规则类型', trigger: 'change' }],
  operator: [{ required: true, message: '请选择操作符', trigger: 'change' }],
  threshold: [{ required: true, message: '静态规则必须填阈值', trigger: 'blur' }],
  level: [{ required: true, message: '请选择级别', trigger: 'change' }]
}

function levelType(l) {
  if (l === 'CRITICAL') return 'danger'
  if (l === 'WARN') return 'warning'
  return 'info'
}

async function load() {
  loading.value = true
  try {
    const params = { current: query.current, size: query.size }
    if (query.name) params.name = query.name
    if (query.ruleType) params.ruleType = query.ruleType
    if (query.enabled !== null && query.enabled !== '') params.enabled = query.enabled
    const page = await getRulePage(params)
    rows.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
  }
}

async function loadTargets() {
  if (targets.value.length === 0) {
    targets.value = await listTarget()
  }
}

async function openDialog(row) {
  Object.assign(form, defaultForm())
  await loadTargets()
  if (row) {
    Object.assign(form, {
      id: row.id,
      name: row.name,
      targetId: row.targetId,
      metricKey: row.metricKey,
      ruleType: row.ruleType,
      operator: row.operator,
      threshold: row.threshold,
      level: row.level,
      durationSec: row.durationSec,
      notifyChannels: typeof row.notifyChannels === 'string' ? row.notifyChannels : JSON.stringify(row.notifyChannels || ['inapp']),
      baselineConfig: typeof row.baselineConfig === 'string' ? row.baselineConfig : JSON.stringify(row.baselineConfig || { days: 7, k: 3 }),
      enabled: row.enabled
    })
  }
  dialogVisible.value = true
}

async function onSave() {
  await formRef.value.validate()
  try { JSON.parse(form.notifyChannels || '[]') } catch (e) { ElMessage.error('通知渠道 JSON 格式错误'); return }
  if (form.ruleType === 'baseline') {
    try { JSON.parse(form.baselineConfig || '{}') } catch (e) { ElMessage.error('基线配置 JSON 格式错误'); return }
  }
  saving.value = true
  try {
    if (form.id) {
      await updateRule(form)
      ElMessage.success('修改成功')
    } else {
      await addRule(form)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function onDelete(row) {
  await ElMessageBox.confirm(`确认删除规则「${row.name}」？`, '提示', { type: 'warning' })
  await deleteRule(row.id)
  ElMessage.success('删除成功')
  load()
}

async function onToggle(row, val) {
  const enabled = val ? 1 : 0
  try {
    await toggleRule(row.id, enabled)
    row.enabled = enabled
    ElMessage.success(enabled === 1 ? '已启用' : '已停用')
  } catch (e) {
    // 失败时静默（开关视觉未持久化）
  }
}

async function onTrain(row) {
  try {
    await trainRule(row.id)
    ElMessage.success('基线训练已触发')
  } catch (e) { /* 已提示 */ }
}

// 干跑
const dryRunVisible = ref(false)
const dryRunResult = ref({ total: 0, timestamps: [], values: [] })
const dryRunRows = ref([])

async function onDryRun(row) {
  try {
    const data = await dryRun(row.id, {})
    dryRunResult.value = data || { total: 0, timestamps: [], values: [] }
    const list = []
    const ts = dryRunResult.value.timestamps || []
    const vs = dryRunResult.value.values || []
    for (let i = 0; i < ts.length; i++) {
      list.push({ time: ts[i], value: vs[i] })
    }
    dryRunRows.value = list
    dryRunVisible.value = true
  } catch (e) { /* 已提示 */ }
}

// 基线图
const baselineVisible = ref(false)
const baselineOption = ref({})

async function onBaseline(row) {
  try {
    // 后端返回 { hours:[0..23], upper[], lower[], actual[] }（x 轴是小时数）
    const today = new Date().toISOString().slice(0, 10)
    const data = await baselineChart(row.id, { date: today })
    const ts = (data.hours || []).map(h => String(h).padStart(2, '0') + ':00')
    const toPairs = arr => (arr || []).map((v, i) => [ts[i], v])
    baselineOption.value = {
      tooltip: { trigger: 'axis' },
      legend: { data: ['upper', 'lower', 'actual'] },
      xAxis: { type: 'category', data: ts },
      yAxis: { type: 'value' },
      series: [
        { name: 'upper', type: 'line', smooth: true, showSymbol: false, data: toPairs(data.upper) },
        { name: 'lower', type: 'line', smooth: true, showSymbol: false, data: toPairs(data.lower) },
        { name: 'actual', type: 'line', smooth: true, showSymbol: false, data: toPairs(data.actual) }
      ]
    }
    baselineVisible.value = true
  } catch (e) { /* 已提示 */ }
}

onMounted(() => {
  load()
  loadTargets()
})
</script>

<style scoped>
.header-bar { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px }
</style>
