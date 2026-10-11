<template>
  <div class="stat-card">
    <div class="num" :class="colorClass">{{ display }}</div>
    <div class="label">{{ label }}</div>
  </div>
</template>

<script setup>
import { computed, ref, onMounted, watch } from 'vue'
import request from '../../../utils/request'

const props = defineProps({ config: { type: Object, required: true } })
const value = ref(null)

/* refType → 名称 + kind */
const refData = {
  alert_pending_count:   { kind: 'alert_pending_count',   label: '待处理告警' },
  incident_open_count:   { kind: 'incident_open_count',   label: '进行中事件' },
  log_anomaly_24h:       { kind: 'log_anomaly_24h',       label: '日志异常 (24h)' },
  llm_call_24h:          { kind: 'llm_call_24h',          label: 'LLM 调用 (24h)' }
}

const label = computed(() => {
  const cfg = props.config
  return cfg?.refType && refData[cfg.refType] ? refData[cfg.refType].label : (cfg?.refType || '未知')
})

const display = computed(() => value.value == null ? '…' : value.value)

const colorClass = computed(() =>
  props.config?.refType === 'alert_pending_count' && value.value > 0 ? 'danger' : 'neutral')

async function fetchVal () {
  const cfg = props.config
  if (!cfg?.refType) return
  const spec = refData[cfg.refType]
  if (!spec) { value.value = '?'; return }
  try {
    // 用 DashboardStatController 统一接口 + 可选 targetId 过滤
    const params = { kind: spec.kind, hours: 24 }
    if (cfg.targetId) params.targetId = cfg.targetId
    const r = await request.get('/api/dashboard-stat/full-count', { params })
    value.value = r?.value ?? 0
  } catch (e) { value.value = 'ERR' }
}

onMounted(fetchVal)
watch(() => [props.config.targetId, props.config.refType], fetchVal)
</script>

<style scoped>
.stat-card { display: flex; flex-direction: column; justify-content: center; align-items: center; height: 100%; text-align: center; }
.num { font-size: 48px; font-weight: 600; line-height: 1; margin-bottom: 8px; }
.num.danger { color: #f56c6c; }
.num.neutral { color: #303133; }
.label { font-size: 12px; color: #909399; }
</style>
