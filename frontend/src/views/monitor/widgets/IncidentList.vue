<template>
  <div class="incident-list">
    <el-table :data="rows" size="small" stripe>
      <el-table-column prop="incidentNo" label="事件号" width="150" show-overflow-tooltip/>
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
import { ref, onMounted } from 'vue'
import request from '../../../utils/request'

const props = defineProps({ config: { type: Object, required: true } })
const rows = ref([])

function statusType (s) {
  switch (s) {
    case 'open': return 'danger'
    case 'processing': return 'warning'
    case 'resolved': return 'success'
    default: return 'info'
  }
}
function statusLabel (s) {
  switch (s) {
    case 'open': return '打开'
    case 'processing': return '处理中'
    case 'resolved': return '已解决'
    default: return s
  }
}

async function load () {
  const status = props.config.status || 'open'
  const limit = props.config.limit || 10
  const r = await request.get('/api/incident/page', {
    params: { current: 1, size: limit, status }
  })
  if (r.code === 200) {
    rows.value = r.data.records || []
  }
}

onMounted(load)
</script>
