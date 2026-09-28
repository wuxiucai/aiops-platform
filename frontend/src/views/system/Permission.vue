<template>
  <div>
    <el-card shadow="hover">
      <template #header>
        <div class="header-bar">
          <span>权限 / 菜单管理</span>
          <el-button v-if="hasPerm('system:permission:add')" type="success" @click="openDialog()">新增节点</el-button>
        </div>
      </template>

      <el-table :data="tree" row-key="id" border default-expand-all v-loading="loading">
        <el-table-column prop="name" label="名称" min-width="200" />
        <el-table-column prop="permType" label="类型" width="80">
          <template #default="{ row }">
            <el-tag :type="row.permType === 'M' ? 'primary' : 'info'">
              {{ row.permType === 'M' ? '菜单' : '按钮' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="path" label="路由/编码" min-width="200">
          <template #default="{ row }">{{ row.permType === 'M' ? row.path : row.perms }}</template>
        </el-table-column>
        <el-table-column prop="component" label="组件" min-width="160" />
        <el-table-column prop="sort" label="排序" width="70" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button v-if="hasPerm('system:permission:update')" link type="primary" @click="openDialog(row)">编辑</el-button>
            <el-button v-if="hasPerm('system:permission:delete')" link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑权限' : '新增权限'" width="520px" destroy-on-close>
      <el-form :model="form" label-width="90px">
        <el-form-item label="父节点 ID">
          <el-input-number v-model="form.parentId" :min="0" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="form.permType">
            <el-radio value="M">菜单</el-radio>
            <el-radio value="B">按钮</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="路由 path" v-if="form.permType === 'M'">
          <el-input v-model="form.path" />
        </el-form-item>
        <el-form-item label="组件" v-if="form.permType === 'M'">
          <el-input v-model="form.component" placeholder="如 monitor/Target" />
        </el-form-item>
        <el-form-item label="权限码" v-if="form.permType === 'B'">
          <el-input v-model="form.perms" placeholder="如 system:user:add" />
        </el-form-item>
        <el-form-item label="图标" v-if="form.permType === 'M'">
          <el-input v-model="form.icon" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { treePermission, addPermission, updatePermission, deletePermission } from '../../api/system'
import { useUserStore } from '../../store/user'

const userStore = useUserStore()
const hasPerm = code => userStore.hasPerm(code)

const tree = ref([])
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const form = reactive({
  id: null, parentId: 0, name: '', permType: 'M',
  path: '', component: '', perms: '', icon: '', sort: 0
})

async function load() {
  loading.value = true
  try {
    tree.value = await treePermission()
  } finally {
    loading.value = false
  }
}

function openDialog(row) {
  Object.assign(form, {
    id: row?.id ?? null,
    parentId: row?.parentId ?? 0,
    name: row?.name ?? '',
    permType: row?.permType ?? 'M',
    path: row?.path ?? '',
    component: row?.component ?? '',
    perms: row?.perms ?? '',
    icon: row?.icon ?? '',
    sort: row?.sort ?? 0
  })
  dialogVisible.value = true
}

async function onSave() {
  saving.value = true
  try {
    if (form.id) {
      await updatePermission(form)
      ElMessage.success('修改成功')
    } else {
      await addPermission(form)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function onDelete(row) {
  await ElMessageBox.confirm(`确认删除「${row.name}」？`, '提示', { type: 'warning' })
  await deletePermission(row.id)
  ElMessage.success('删除成功')
  load()
}

onMounted(load)
</script>

<style scoped>
.header-bar { display: flex; justify-content: space-between; align-items: center }
</style>
