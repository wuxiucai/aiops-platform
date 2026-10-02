<template>
  <div>
    <el-card shadow="hover">
      <div class="page-head">
        <h2>监控分组</h2>
        <el-button type="primary" size="small" @click="openForm(null)" v-if="hasPerm('monitor:target:add')">
          <el-icon><Plus /></el-icon> 新建分组
        </el-button>
      </div>

      <el-table :data="rows" border stripe v-loading="loading" empty-text="暂无数据">
        <el-table-column prop="id" label="ID" width="60"/>
        <el-table-column prop="name" label="名称" min-width="150"/>
        <el-table-column prop="description" label="描述" min-width="250"/>
        <el-table-column prop="sort" label="排序" width="80"/>
        <el-table-column prop="createTime" label="创建时间" width="180"/>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openForm(row)" v-if="hasPerm('monitor:target:update')">编辑</el-button>
            <el-button link type="danger" @click="onDelete(row)" v-if="hasPerm('monitor:target:delete')">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建/编辑 dialog -->
    <el-dialog v-model="dialog" :title="form.id ? '编辑分组' : '新建分组'" width="520px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如生产环境 / 办公网段 / 云平台"/>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" placeholder="选填"/>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" :max="999" style="width: 150px"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog=false">取消</el-button>
        <el-button type="primary" @click="save" :loading="saving">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../../utils/request'
import { useUserStore } from '../../store/user'

const userStore = useUserStore()
const hasPerm = (code) => userStore.hasPerm(code)

const rows = ref([])
const loading = ref(false)
const saving = ref(false)
const dialog = ref(false)
const form = ref({ id: null, name: '', description: '', sort: 0 })

async function load () {
  loading.value = true
  try {
    const r = await request.get('/api/monitor/group/list')
    rows.value = r.data || []
  } finally { loading.value = false }
}

function openForm (row) {
  form.value = row
    ? { id: row.id, name: row.name, description: row.description || '', sort: row.sort || 0 }
    : { id: null, name: '', description: '', sort: 0 }
  dialog.value = true
}

async function save () {
  if (!form.value.name) {
    ElMessage.warning('名称必填')
    return
  }
  saving.value = true
  try {
    if (form.value.id) await request.put('/api/monitor/group', form.value)
    else               await request.post('/api/monitor/group', form.value)
    ElMessage.success('保存成功')
    dialog.value = false
    load()
  } catch (e) { /* 拦截器已弹错误 */ } finally { saving.value = false }
}

async function onDelete (row) {
  try {
    await ElMessageBox.confirm(
      `确定删除分组"${row.name}"？该分组下的 target 不会被删除。`,
      '删除分组',
      { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' }
    )
  } catch { return }
  try {
    await request.delete(`/api/monitor/group/${row.id}`)
    ElMessage.success('已删除')
    load()
  } catch (e) { /* 拦截器已弹错误 */ }
}

onMounted(load)
</script>

<style scoped>
.page-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-head h2 { margin: 0; font-size: 20px; }
</style>
