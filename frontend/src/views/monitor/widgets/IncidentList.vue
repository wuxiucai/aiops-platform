<template>
  <div class="incident-list">
    <el-table :data="rows" size="small" stripe>
      <el-table-column prop="incident_no" label="事件号" width="150" show-overflow-tooltip/>
      <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip/>
      <el-table-column label="级别" width="80">
        <template #default="{ row }">
          <el-tag :type="row.level === 'CRITICAL' ? 'danger' : 'warning'" size="small">{{ row.level }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="rows.length === 0" description="暂无进行中事件" :image-size="40"/>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { getStatIncidentPage } from '../../../api/dashboard'

const props = defineProps({ config: { type: Object, required: true } })
const rows = ref([])

function statusType (s) {
  if (s === 'open') return 'danger'
  if (s === 'processing') return 'warning'
  if (s === 'resolved') return 'success'
  return 'info'
}
function statusLabel (s) {
  if (s === 'open') return '打开'
  if (s === 'processing') return '处理中'
  if (s === 'resolved') return '已解决'
  return s
}

async function load () {
  const params = {
    size: props.config.limit || 10,
    status: props.config.status || 'open'
  }
  if (props.config.targetId) params.targetId = props.config.targetId
  const r = await getStatIncidentPage(params)
  rows.value = (r && r.records) || []
}

onMounted(load)
watch(() => [props.config.targetId, props.config.status], load)
</script>
