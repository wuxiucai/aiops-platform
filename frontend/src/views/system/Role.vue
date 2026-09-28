<template>
  <div>
    <el-card shadow="hover">
      <template #header>
        <div class="header-bar">
          <span>角色管理</span>
          <el-button v-if="hasPerm('system:role:add')" type="success" @click="openDialog()">新增角色</el-button>
        </div>
      </template>

      <el-table :data="rows" border stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="roleCode" label="角色编码" width="140" />
        <el-table-column prop="roleName" label="角色名称" width="180" />
        <el-table-column prop="description" label="说明" min-width="220" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'">
              {{ row.status === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button v-if="hasPerm('system:role:update')" link type="primary" @click="openDialog(row)">编辑</el-button>
            <el-button v-if="hasPerm('system:role:delete')" link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.current"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        style="margin-top: 12px; justify-content: flex-end"
        @change="load"
      />
    </el-card>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑角色' : '新增角色'" width="640px" destroy-on-close>
      <el-form :model="form" :rules="rules" ref="formRef" label-width="90px">
        <el-form-item label="角色编码" prop="roleCode">
          <el-input v-model="form.roleCode" :disabled="!!form.id" placeholder="如 OPS" />
        </el-form-item>
        <el-form-item label="角色名称" prop="roleName">
          <el-input v-model="form.roleName" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="权限">
          <el-tree
            ref="treeRef"
            :data="permTree"
            show-checkbox
            node-key="id"
            :props="{ label: 'name', children: 'children' }"
            default-expand-all
            style="width: 100%; max-height: 320px; overflow: auto"
          />
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
import { pageRole, addRole, updateRole, deleteRole, treePermission } from '../../api/system'
import { useUserStore } from '../../store/user'

const userStore = useUserStore()
const hasPerm = code => userStore.hasPerm(code)

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const formRef = ref()
const treeRef = ref()
const permTree = ref([])

const query = reactive({ current: 1, size: 10 })
const form = reactive({ id: null, roleCode: '', roleName: '', description: '', status: 1, permissionIds: [] })

const rules = {
  roleCode: [{ required: true, message: '角色编码不能为空', trigger: 'blur' }],
  roleName: [{ required: true, message: '角色名称不能为空', trigger: 'blur' }]
}

async function load() {
  loading.value = true
  try {
    const page = await pageRole(query)
    rows.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
  }
}

async function openDialog(row) {
  Object.assign(form, { id: null, roleCode: '', roleName: '', description: '', status: 1, permissionIds: [] })
  if (row) {
    Object.assign(form, {
      id: row.id, roleCode: row.roleCode, roleName: row.roleName,
      description: row.description, status: row.status, permissionIds: []
    })
  }
  if (permTree.value.length === 0) {
    permTree.value = await treePermission()
  }
  dialogVisible.value = true
  // 清空上次勾选
  setTimeout(() => treeRef.value?.setCheckedKeys([]))
}

async function onSave() {
  await formRef.value.validate()
  // 收集勾选 + 半选父节点
  const checked = treeRef.value.getCheckedKeys()
  const half = treeRef.value.getHalfCheckedKeys()
  form.permissionIds = [...half, ...checked]
  saving.value = true
  try {
    if (form.id) {
      await updateRole(form)
      ElMessage.success('修改成功')
    } else {
      await addRole(form)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function onDelete(row) {
  await ElMessageBox.confirm(`确认删除角色「${row.roleName}」？`, '提示', { type: 'warning' })
  await deleteRole(row.id)
  ElMessage.success('删除成功')
  load()
}

onMounted(load)
</script>

<style scoped>
.header-bar { display: flex; justify-content: space-between; align-items: center }
</style>
