<template>
  <div class="top-template">
    <el-table :data="rows" size="small" stripe>
      <el-table-column prop="template" label="日志模板" min-width="240" show-overflow-tooltip>
        <template #default="{ row }">
          <span>{{ (row.template || '').substring(0, 80) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="cnt" label="窗口计数" width="90" align="right"/>
    </el-table>
    <el-empty v-if="rows.length === 0" description="暂无模板" :image-size="40"/>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { getStatTopTemplate } from '../../../api/dashboard'

const props = defineProps({ config: { type: Object, required: true } })
const rows = ref([])

async function load () {
  const params = { limit: props.config.limit || 5 }
  if (props.config.service) params.service = props.config.service
  const r = await getStatTopTemplate(params)
  rows.value = Array.isArray(r) ? r : []
}

onMounted(load)
watch(() => [props.config.service], load)
</script>
