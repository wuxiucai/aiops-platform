<template>
  <el-dialog v-model="visible" title="添加 Widget" width="640px">
    <el-form :model="form" label-width="100px">
      <el-form-item label="类型" required>
        <el-select v-model="form.widgetType" style="width: 100%">
          <el-option value="stat_card"     label="stat_card — 单一大数字 (KPI 卡片)"/>
          <el-option value="line_chart"    label="line_chart — 时序折线"/>
          <el-option value="top_alert"     label="top_alert — Top 告警"/>
          <el-option value="top_template"  label="top_template — Top 日志模板"/>
          <el-option value="incident_list" label="incident_list — 进行中事件"/>
          <el-option value="metric_compare" label="metric_compare — 多指标对比"/>
        </el-select>
      </el-form-item>
      <el-form-item label="标题" required>
        <el-input v-model="form.title" placeholder="如 CPU 使用率"/>
      </el-form-item>

      <!-- ========== stat_card 字段 ========== -->
      <template v-if="form.widgetType === 'stat_card'">
        <el-form-item label="统计对象" required>
          <el-select v-model="form.refType" style="width: 100%">
            <el-option value="alert_pending_count" label="待处理告警数 (24h pending)"/>
            <el-option value="incident_open_count" label="进行中故障数 (open)"/>
            <el-option value="log_anomaly_24h" label="日志异常数 (24h)"/>
            <el-option value="llm_call_24h" label="LLM 调用数 (24h)"/>
          </el-select>
        </el-form-item>
        <el-form-item label="监控对象（可选，留空为全部）">
          <el-select v-model="form.targetId" clearable placeholder="全部对象">
            <el-option v-for="t in targets" :key="t.id" :value="t.id" :label="`${t.name} (${t.ip})`"/>
          </el-select>
        </el-form-item>
      </template>

      <!-- ========== line_chart / metric_compare 字段 ========== -->
      <template v-else-if="form.widgetType === 'line_chart' || form.widgetType === 'metric_compare'">
        <el-form-item label="监控对象" required>
          <el-select v-model="form.targetId" style="width: 100%">
            <el-option v-for="t in targets" :key="t.id" :value="t.id" :label="`${t.name} (${t.ip})`"/>
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.widgetType === 'metric_compare'" label="指标(多选)" required>
          <el-select v-model="form.metricKeys" multiple style="width: 100%">
            <el-option v-for="m in metricDefs" :key="m.metricKey" :value="m.metricKey" :label="`${m.metricName} (${m.metricKey})`"/>
          </el-select>
        </el-form-item>
        <el-form-item v-else label="指标" required>
          <el-select v-model="form.metricKey" style="width: 100%">
            <el-option v-for="m in metricDefs" :key="m.metricKey" :value="m.metricKey" :label="`${m.metricName} (${m.metricKey})`"/>
          </el-select>
        </el-form-item>
        <el-form-item label="时间范围">
          <el-select v-model="form.timeRangeHours">
            <el-option :value="1" label="1 小时"/>
            <el-option :value="6" label="6 小时"/>
            <el-option :value="24" label="24 小时"/>
            <el-option :value="72" label="3 天"/>
          </el-select>
        </el-form-item>
      </template>

      <!-- ========== top_alert / top_template / incident_list 字段 ========== -->
      <template v-else-if="form.widgetType === 'top_alert' || form.widgetType === 'top_template' || form.widgetType === 'incident_list'">
        <el-form-item label="数量限制">
          <el-input-number v-model="form.limit" :min="1" :max="20"/>
        </el-form-item>
        <!-- top_alert: targetId 可选过滤 -->
        <el-form-item v-if="form.widgetType === 'top_alert'" label="监控对象（可选）">
          <el-select v-model="form.targetId" clearable placeholder="全部对象">
            <el-option v-for="t in targets" :key="t.id" :value="t.id" :label="`${t.name} (${t.ip})`"/>
          </el-select>
        </el-form-item>
        <!-- top_template: service 可选过滤 -->
        <el-form-item v-if="form.widgetType === 'top_template'" label="日志服务（可选）">
          <el-select v-model="form.service" clearable placeholder="全部服务">
            <el-option v-for="s in logServices" :key="s" :value="s" :label="s"/>
          </el-select>
        </el-form-item>
        <!-- incident_list: targetId 可选过滤 -->
        <el-form-item v-if="form.widgetType === 'incident_list'" label="监控对象（可选）">
          <el-select v-model="form.targetId" clearable placeholder="全部对象">
            <el-option v-for="t in targets" :key="t.id" :value="t.id" :label="`${t.name} (${t.ip})`"/>
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.widgetType !== 'incident_list'" label="时间范围(h)">
          <el-select v-model="form.timeRangeHours">
            <el-option :value="1" label="1 小时"/>
            <el-option :value="24" label="24 小时"/>
          </el-select>
        </el-form-item>
        <el-form-item v-else label="状态" required>
          <el-select v-model="form.status">
            <el-option value="open" label="进行中"/>
            <el-option value="processing" label="处理中"/>
            <el-option value="resolved" label="已解决"/>
          </el-select>
        </el-form-item>
      </template>
    </el-form>
    <template #footer>
      <el-button @click="visible=false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="onSubmit">添加</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, reactive, watch, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { pageTarget, getMetricDefinitions } from '../../../api/monitor'
import { listLogServices } from '../../../api/dashboard'

/**
 * 添加 Widget 对话框：按类型收集参数并拼成 widget 定义 { widgetType, title, config }。
 * 写入模板由父组件的 submit 回调完成；回调成功后对话框关闭并清空表单，失败则保持打开。
 */
const props = defineProps({
  submit: { type: Function, required: true }
})
const visible = defineModel({ type: Boolean, default: false })

const targets = ref([])
const metricDefs = ref([])
const logServices = ref([])
const submitting = ref(false)

const form = reactive({
  widgetType: 'line_chart',
  title: '',
  refType: 'alert_pending_count',
  targetId: null,
  metricKey: '',
  metricKeys: [],
  timeRangeHours: 1,
  limit: 5,
  status: 'open',
  service: null
})

/** 按类型取所需字段，可选过滤项留空则不写入 config。缺必填项时返回提示文案 */
function buildConfig () {
  switch (form.widgetType) {
    case 'stat_card':
      return { refType: form.refType, ...(form.targetId ? { targetId: form.targetId } : {}) }
    case 'line_chart':
      if (!form.targetId || !form.metricKey) return '选择监控对象与指标'
      return { targetId: form.targetId, metricKey: form.metricKey, timeRangeHours: form.timeRangeHours }
    case 'metric_compare':
      if (!form.targetId || !form.metricKeys?.length) return '选择监控对象与指标'
      return { targetId: form.targetId, metricKeys: form.metricKeys, timeRangeHours: form.timeRangeHours }
    case 'top_alert':
      return { limit: form.limit, timeRangeHours: form.timeRangeHours, ...(form.targetId ? { targetId: form.targetId } : {}) }
    case 'top_template':
      return { limit: form.limit, timeRangeHours: form.timeRangeHours, ...(form.service ? { service: form.service } : {}) }
    case 'incident_list':
      return { limit: form.limit, status: form.status, ...(form.targetId ? { targetId: form.targetId } : {}) }
    default:
      return {}
  }
}

async function onSubmit () {
  if (!form.title) {
    ElMessage.warning('标题必填')
    return
  }
  const config = buildConfig()
  if (typeof config === 'string') {
    ElMessage.warning(config)
    return
  }
  submitting.value = true
  try {
    await props.submit({ widgetType: form.widgetType, title: form.title, config })
    visible.value = false
    form.refType = 'alert_pending_count'
    form.metricKey = ''
    form.metricKeys = []
  } catch (e) { /* 拦截器已弹错，保持对话框打开 */ } finally {
    submitting.value = false
  }
}

/* 每次打开清空标题 */
watch(visible, v => { if (v) form.title = '' })

async function loadOptions () {
  try {
    const t = await pageTarget({ current: 1, size: 100 })
    targets.value = t.records || []
  } catch (e) { /* 静默 */ }
  try {
    const m = await getMetricDefinitions()
    metricDefs.value = Array.isArray(m) ? m : (m?.records || [])
  } catch (e) { /* 静默 */ }
  try {
    const s = await listLogServices()
    logServices.value = Array.isArray(s) ? s : []
  } catch (e) { /* 静默 */ }
}

onMounted(loadOptions)
</script>
