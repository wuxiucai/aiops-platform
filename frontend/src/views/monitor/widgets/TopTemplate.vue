<template>
  <div class="top-template">
    <el-table :data="rows" size="small" stripe>
      <el-table-column prop="template" label="日志模板" min-width="240" show-overflow-tooltip/>
      <el-table-column prop="count" label="窗口计数" width="90" align="right"/>
    </el-table>
    <el-empty v-if="rows.length === 0" description="暂无模板" :image-size="40"/>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import request from '../../../utils/request'

const props = defineProps({ config: { type: Object, required: true } })
const rows = ref([])

async function load () {
  const hours = props.config.timeRangeHours || 1
  const r = await request.get('/api/log/template/page', {
    params: { current: 1, size: 100 }
  })
  if (r.code === 200) {
    const list = (r.data.records || [])
      .sort((a, b) => (b.totalCount || 0) - (a.totalCount || 0))
      .slice(0, props.config.limit || 5)
    rows.value = list.map(t => ({
      template: t.templateText ? t.templateText.substring(0, 80) : '-',
      count: t.totalCount
    }))
  }
}

onMounted(load)
</script>
