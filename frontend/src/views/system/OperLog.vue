<template>
  <div>
    <el-card shadow="hover">
      <template #header>
        <div class="header-bar">
          <span>操作日志</span>
          <div>
            <el-input v-model="query.module" placeholder="模块名，如 system" clearable
                      style="width: 200px; margin-right: 8px" @keyup.enter="load" />
            <el-button type="primary" @click="load">查询</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" border stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="username" label="用户" width="110" />
        <el-table-column prop="module" label="模块" width="110" />
        <el-table-column prop="operation" label="操作" width="140" />
        <el-table-column prop="method" label="HTTP" width="70" />
        <el-table-column prop="requestUri" label="URI" min-width="200" show-overflow-tooltip />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'">
              {{ row.status === 1 ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="errorMsg" label="错误信息" min-width="200" show-overflow-tooltip />
        <el-table-column prop="costTime" label="耗时(ms)" width="90" />
        <el-table-column prop="createTime" label="时间" width="170" />
      </el-table>

      <el-pagination
        v-model:current-page="query.current"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        style="margin-top: 12px; justify-content: flex-end"
        @change="load"
      />
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { pageOperLog } from '../../api/system'

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const query = reactive({ current: 1, size: 20, module: '' })

async function load() {
  loading.value = true
  try {
    const page = await pageOperLog(query)
    rows.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.header-bar { display: flex; justify-content: space-between; align-items: center }
</style>
