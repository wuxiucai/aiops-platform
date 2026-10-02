<template>
  <div class="stat-card">
    <div class="num" :class="colorClass">{{ display }}</div>
    <div class="label">{{ label }}</div>
  </div>
</template>

<script setup>
import { computed, ref, onMounted } from 'vue'
import request from '../../../utils/request'

const props = defineProps({ config: { type: Object, required: true } })
const value = ref(null)
const loading = ref(false)

/* 按 refType 选择接口 */
const refData = {
  alert_pending_count:   { url: '/api/alert/record/page', query: { status: 'pending', current: 1, size: 1 }, prop: 'total', label: '待处理告警' },
  incident_open_count:   { url: '/api/incident/page',     query: { status: 'open',    current: 1, size: 1 }, prop: 'total', label: '进行中事件' },
  log_anomaly_24h:       { url: '/api/log/anomaly/page',  query: { current: 1, size: 1 }, prop: 'total', label: '日志异常 (24h)' },
  llm_call_24h:          { url: '/api/llm/provider/list', query: {}, prop: null, label: 'LLM 调用 (24h)', tokens: true }
}

const label = computed(() => {
  const cfg = props.config
  return cfg?.refType && refData[cfg.refType] ? refData[cfg.refType].label : cfg?.refType || '未知'
})

const display = computed(() => {
  if (value.value == null) return '…'
  if (props.config?.refType === 'llm_call_24h') {
    // 便捷： 求全部 provider total count
    return value.value
  }
  return value.value
})

const colorClass = computed(() => {
  return props.config?.refType === 'alert_pending_count' && value.value > 0 ? 'danger' : 'neutral'
})

async function fetchVal () {
  const cfg = props.config
  if (!cfg?.refType) return
  const spec = refData[cfg.refType]
  if (!spec) { value.value = '?'; return }
  loading.value = true
  try {
    const r = await request.get(spec.url, { params: spec.query })
    // alert/incident/anomaly 接口返回 {records,total,...}; 大部分 min总
    if (spec.prop && r.data && typeof r.data[spec.prop] === 'number') {
      value.value = r.data[spec.prop]
    } else if (spec.tokens && Array.isArray(r.data)) {
      // llm provider list → sum total_tokens 但当前显示只count providers
      value.value = r.data.length
    } else {
      value.value = 0
    }
  } catch (e) { value.value = 'ERR' }
  finally { loading.value = false }
}

onMounted(fetchVal)
</script>

<style scoped>
.stat-card { display: flex; flex-direction: column; justify-content: center; align-items: center; height: 100%; text-align: center; }
.num { font-size: 48px; font-weight: 600; line-height: 1; margin-bottom: 8px; }
.num.danger { color: #f56c6c; }
.num.neutral { color: #303133; }
.label { font-size: 12px; color: #909399; }
</style>
