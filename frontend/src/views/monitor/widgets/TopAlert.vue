<template>
  <div class="top-alert">
    <el-table :data="rows" size="small" stripe>
      <el-table-column prop="title" label="告警" min-width="180" show-overflow-tooltip/>
      <el-table-column prop="count" label="触发次数" width="90" align="right"/>
    </el-table>
    <el-empty v-if="rows.length === 0" description="暂无告警" :image-size="40"/>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import request from '../../../utils/request'

const props = defineProps({ config: { type: Object, required: true } })
const rows = ref([])

async function load () {
  const hours = props.config.timeRangeHours || 24
  const r = await request.get('/api/alert/record/page', {
    params: { current: 1, size: 50, timeRangeHours: hours }
  })
  if (r.code === 200) {
    const list = (r.data.records || []).sort((a, b) => (b.triggerCount || 0) - (a.triggerCount || 0)).slice(0, props.config.limit || 5)
    rows.value = list.map(r => ({ title: r.title, count: r.triggerCount }))
  }
}

onMounted(load)
</script>
