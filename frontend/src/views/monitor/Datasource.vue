<template>
  <div>
    <el-card shadow="hover">
      <template #header>
        <div class="header-bar">
          <span>ES 数据源管理</span>
          <el-button type="success" @click="openDialog()" v-if="hasPerm('es:datasource:add')">新增数据源</el-button>
        </div>
      </template>

      <el-table :data="rows" border stripe v-loading="loading" empty-text="暂无数据源">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="name" label="名称" min-width="160" show-overflow-tooltip />
        <el-table-column label="连接地址" min-width="200">
          <template #default="{ row }">
            <code>{{ row.esScheme }}://{{ row.esHost }}:{{ row.esPort }}</code>
          </template>
        </el-table-column>
        <el-table-column prop="esVersion" label="版本" width="100" />
        <el-table-column prop="clusterName" label="集群" min-width="160" show-overflow-tooltip />
        <el-table-column label="默认" width="80" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.isDefault === 1" type="success" size="small">默认</el-tag>
            <el-button v-else link type="primary" size="small" @click="setDefault(row)" v-if="hasPerm('es:datasource:update')">设默认</el-button>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="row.testResult?.startsWith('OK') ? 'success' : (row.testResult ? 'danger' : 'info')">
              {{ row.testResult ? row.testResult : '未测试' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastTestTime" label="最近测试" width="170" />
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="test(row)" :loading="testingId === row.id">测试</el-button>
            <el-button link type="primary" @click="openDialog(row)" v-if="hasPerm('es:datasource:update')">编辑</el-button>
            <el-button link type="danger" @click="onDelete(row)" v-if="hasPerm('es:datasource:delete')">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建/编辑 dialog -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑数据源' : '新增数据源'" width="600px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如 elk-cluster-prod" />
        </el-form-item>
        <el-form-item label="协议">
          <el-select v-model="form.esScheme" style="width: 140px">
            <el-option value="http" label="http" />
            <el-option value="https" label="https" />
          </el-select>
        </el-form-item>
        <el-form-item label="主机" required>
          <el-input v-model="form.esHost" placeholder="10.0.0.91" />
        </el-form-item>
        <el-form-item label="端口" required>
          <el-input-number v-model="form.esPort" :min="1" :max="65535" />
        </el-form-item>
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="elastic（可选）" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password :placeholder="form.id ? '留空则不修改' : '可选'" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="form.apiKey" :placeholder="form.id ? '留空则不修改' : '可选'" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save" :loading="saving">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../../utils/request'
import { useUserStore } from '../../store/user'

const userStore = useUserStore()
const hasPerm = (code) => userStore.hasPerm(code)

const rows = ref([])
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const testingId = ref(null)
const form = ref(defaultForm())

function defaultForm () {
  return { id: null, name: '', esScheme: 'http', esHost: '', esPort: 9200, username: '', password: '', apiKey: '', remark: '' }
}

async function load () {
  loading.value = true
  try {
    const r = await request.get('/api/es/datasource/list')
    rows.value = r || []
  } finally { loading.value = false }
}

function openDialog (row) {
  form.value = row
    ? { id: row.id, name: row.name, esScheme: row.esScheme || 'http', esHost: row.esHost, esPort: row.esPort, username: row.username || '', password: '', apiKey: '', remark: row.remark || '' }
    : defaultForm()
  dialogVisible.value = true
}

async function save () {
  if (!form.value.name || !form.value.esHost || !form.value.esPort) {
    ElMessage.warning('名称/主机/端口 必填')
    return
  }
  saving.value = true
  try {
    // 后端 service 接受 passwordEnc/apiKey 明文（自己再 AES 一次），留空表示不修改
    const body = {
      id: form.value.id,
      name: form.value.name,
      esScheme: form.value.esScheme,
      esHost: form.value.esHost,
      esPort: form.value.esPort,
      username: form.value.username,
      passwordEnc: form.value.password || undefined,
      apiKey: form.value.apiKey || undefined,
      remark: form.value.remark
    }
    if (form.value.id) await request.put('/api/es/datasource', body)
    else               await request.post('/api/es/datasource', body)
    ElMessage.success('已保存')
    dialogVisible.value = false
    load()
  } catch (e) { /* 拦截器已弹错 */ } finally { saving.value = false }
}

async function test (row) {
  testingId.value = row.id
  try {
    const r = await request.post(`/api/es/datasource/${row.id}/test`)
    const ok = r?.ok === true || (r?.testResult || '').startsWith('OK')
    ElMessage[ok ? 'success' : 'warning'](r?.testResult || (ok ? '连接 OK' : '连接失败'))
    load()
  } catch (e) { /* 拦截器已弹错 */ } finally { testingId.value = null }
}

async function setDefault (row) {
  try {
    await request.put(`/api/es/datasource/${row.id}/default`)
    ElMessage.success(`已设 ${row.name} 为默认`)
    load()
  } catch (e) { /* 拦截器已弹错 */ }
}

async function onDelete (row) {
  try {
    await ElMessageBox.confirm(`确定删除数据源 "${row.name}"？`, '删除',
      { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' })
  } catch { return }
  try {
    await request.delete(`/api/es/datasource/${row.id}`)
    ElMessage.success('已删除')
    load()
  } catch (e) { /* 拦截器已弹错 */ }
}

onMounted(load)
</script>

<style scoped>
.header-bar { display: flex; justify-content: space-between; align-items: center; }
</style>
