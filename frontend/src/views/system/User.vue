<template>
  <div>
    <el-card shadow="hover">
      <template #header>
        <div class="header-bar">
          <span>用户管理</span>
          <div>
            <el-input
              v-model="query.keyword"
              placeholder="用户名 / 昵称"
              clearable
              style="width: 220px; margin-right: 8px"
              @keyup.enter="load"
            />
            <el-button type="primary" @click="load">查询</el-button>
            <el-button v-if="hasPerm('system:user:add')" type="success" @click="openDialog()">新增用户</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" border stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="username" label="用户名" width="140" />
        <el-table-column prop="nickname" label="昵称" width="140" />
        <el-table-column prop="email" label="邮箱" min-width="180" />
        <el-table-column prop="phone" label="电话" width="140" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'">
              {{ row.status === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="170" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button v-if="hasPerm('system:user:update')" link type="primary" @click="openDialog(row)">编辑</el-button>
            <el-button v-if="hasPerm('system:user:delete')" link type="danger" @click="onDelete(row)">删除</el-button>
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

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑用户' : '新增用户'" width="520px" destroy-on-close>
      <el-form :model="form" :rules="rules" ref="formRef" label-width="90px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" :disabled="!!form.id" />
        </el-form-item>
        <el-form-item :label="form.id ? '重置密码' : '密码'" :prop="form.id ? '' : 'password'">
          <el-input v-model="form.password" type="password" show-password
                    :placeholder="form.id ? '留空表示不修改' : '6~64 位'" />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="form.nickname" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="form.email" />
        </el-form-item>
        <el-form-item label="电话">
          <el-input v-model="form.phone" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.roleIds" multiple style="width: 100%">
            <el-option v-for="r in roleOptions" :key="r.id" :label="r.roleName" :value="r.id" />
          </el-select>
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
import { pageUser, addUser, updateUser, deleteUser, allRole } from '../../api/system'
import { useUserStore } from '../../store/user'

const userStore = useUserStore()
const hasPerm = code => userStore.hasPerm(code)

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const formRef = ref()
const roleOptions = ref([])

const query = reactive({ current: 1, size: 10, keyword: '' })
const form = reactive({ id: null, username: '', password: '', nickname: '', email: '', phone: '', status: 1, roleIds: [] })

const rules = {
  username: [{ required: true, message: '用户名不能为空', trigger: 'blur' }],
  password: [
    { required: true, message: '密码不能为空', trigger: 'blur' },
    { min: 6, max: 64, message: '密码长度 6~64', trigger: 'blur' }
  ]
}

async function load() {
  loading.value = true
  try {
    const page = await pageUser(query)
    rows.value = page.records
    total.value = page.total
  } finally {
    loading.value = false
  }
}

async function openDialog(row) {
  Object.assign(form, { id: null, username: '', password: '', nickname: '', email: '', phone: '', status: 1, roleIds: [] })
  if (row) {
    Object.assign(form, {
      id: row.id, username: row.username, password: '',
      nickname: row.nickname, email: row.email, phone: row.phone,
      status: row.status, roleIds: []
    })
  }
  if (roleOptions.value.length === 0) {
    roleOptions.value = await allRole()
  }
  dialogVisible.value = true
}

async function onSave() {
  await formRef.value.validate()
  saving.value = true
  try {
    if (form.id) {
      await updateUser(form)
      ElMessage.success('修改成功')
    } else {
      await addUser(form)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function onDelete(row) {
  await ElMessageBox.confirm(`确认删除用户「${row.username}」？`, '提示', { type: 'warning' })
  await deleteUser(row.id)
  ElMessage.success('删除成功')
  load()
}

onMounted(load)
</script>

<style scoped>
.header-bar { display: flex; justify-content: space-between; align-items: center }
</style>
