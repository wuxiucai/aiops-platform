<template>
  <div class="top-alert">
    <el-table :data="rows" size="small" stripe>
      <el-table-column prop="title" label="告警" min-width="180" show-overflow-tooltip/>
      <el-table-column prop="cnt" label="触发次数" width="90" align="right"/>
    </el-table>
    <el-empty v-if="rows.length === 0" description="暂无告警" :image-size="40"/>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import request from '../../../utils/request'

const props = defineProps({ config: { type: Object, required: true } })
const rows = ref([])

async function load () {
  const params = {
    limit: props.config.limit || 5,
    hours: props.config.timeRangeHours || 24
  }
  if (props.config.targetId) params.targetId = props.config.targetId
  const r = await request.get('/api/dashboard-stat/top-alert', { params })
  rows.value = Array.isArray(r) ? r : []
}

onMounted(load)
watch(() => [props.config.targetId], load)
</script>
