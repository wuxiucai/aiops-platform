<template>
  <div class="mail-config-page">
    <div class="page-head">
      <h2>邮件 SMTP 配置</h2>
      <el-button type="primary" @click="openForm(null)" v-if="hasPerm('system:mail:add')">
        <el-icon><Plus /></el-icon> 新建配置
      </el-button>
    </div>

    <el-card shadow="never" v-loading="loading">
      <el-table :data="rows" border stripe>
        <el-table-column prop="id" label="ID" width="60"/>
        <el-table-column prop="name" label="配置名" min-width="150"/>
        <el-table-column prop="smtpHost" label="SMTP Host" min-width="180"/>
        <el-table-column prop="smtpPort" label="Port" width="80"/>
        <el-table-column prop="username" label="账号" min-width="200"/>
        <el-table-column label="授权码" width="110">
          <template #default>
            <code class="password-mask">****</code>
          </template>
        </el-table-column>
        <el-table-column label="SSL" width="70" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.ssl === 1" type="success" size="small">SSL</el-tag>
            <el-tag v-else type="info" size="small">Plain</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="默认" width="80" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.isDefault === 1" type="success" size="small">默认</el-tag>
            <el-button v-else link size="small" @click="setDefault(row)" v-if="hasPerm('system:mail:update')">设为默认</el-button>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="90" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.enabled" :active-value="1" :inactive-value="0" @change="toggle(row)"/>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="320" fixed="right">
          <template #default="{ row }">
            <el-button link size="small" type="primary" @click="openForm(row)" v-if="hasPerm('system:mail:update')">编辑</el-button>
            <el-button link size="small" @click="testSend(row)">测试发送</el-button>
            <el-button link size="small" type="danger" @click="del(row)" v-if="hasPerm('system:mail:delete')">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="rows.length === 0 && !loading" description="暂无 SMTP 配置， 点右上角新增"/>
    </el-card>

    <!-- 新建/编辑 dialog -->
    <el-dialog v-model="dialog" :title="form.id ? '编辑配置' : '新建配置'" width="600px">
      <el-form :model="form" label-width="120px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如 QQ-告警"/>
        </el-form-item>
        <el-form-item label="SMTP 主机" required>
          <el-input v-model="form.smtpHost" placeholder="如 smtp.qq.com"/>
        </el-form-item>
        <el-form-item label="SMTP 端口" required>
          <el-input-number v-model="form.smtpPort" :min="25" :max="587" :step="1"/>
        </el-form-item>
        <el-form-item label="账号 （邮箱）" required>
          <el-input v-model="form.username" placeholder="123456@qq.com"/>
        </el-form-item>
        <el-form-item :label="form.id ? '授权码（不改则留空）' : '授权码'" :required="!form.id">
          <el-input v-model="form.passwordEnc" type="password" show-password placeholder="QQ 邮箱授权码" autocomplete="new-password"/>
        </el-form-item>
        <el-form-item label="发件人名">
          <el-input v-model="form.fromName" placeholder="AIOps 告警"/>
        </el-form-item>
        <el-form-item label="SSL">
          <el-switch v-model="form.ssl" :active-value="1" :inactive-value="0"/>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" :active-value="1" :inactive-value="0"/>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog=false">取消</el-button>
        <el-button type="primary" @click="save" :loading="saving">保存</el-button>
      </template>
    </el-dialog>

    <!-- 测试发送 dialog -->
    <el-dialog v-model="testVisible" :title="`测试发送到：${testForm.configName}`" width="500px">
      <el-form :model="testForm" label-width="100px">
        <el-form-item label="接收邮箱" required>
          <el-input v-model="testForm.to" placeholder="someone@example.com"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="testVisible=false">取消</el-button>
        <el-button type="primary" @click="doTestSend" :loading="testSending">发送</el-button>
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
const testVisible = ref(false)
const testSending = ref(false)
const form = ref(defaultForm())
const testForm = reactive({ configName: '', configId: null, to: '' })

function defaultForm () {
  return {
    id: null, name: '', smtpHost: '', smtpPort: 465,
    username: '', passwordEnc: '', fromName: 'AIOps 告警',
    ssl: 1, enabled: 1, isDefault: 0, remark: ''
  }
}

async function load () {
  loading.value = true
  try {
    const r = await request.get('/api/system/mail-config/list')
    rows.value = r || []
  } finally { loading.value = false }
}

function openForm (row) {
  if (row) {
    form.value = { ...row, passwordEnc: '' }  // 授权码不回传
  } else {
    form.value = defaultForm()
  }
  dialog.value = true
}

async function save () {
  saving.value = true
  try {
    if (form.value.id) await request.put('/api/system/mail-config', form.value)
    else               await request.post('/api/system/mail-config', form.value)
    ElMessage.success('保存成功')
    dialog.value = false
    load()
  } catch (e) { /* 拦截器已弹错误 */ } finally { saving.value = false }
}

async function setDefault (row) {
  const ok = await ElMessageBox.confirm(`确定把 ${row.name} 设为默认 SMTP 配置吗？`, '设为默认', { type: 'warning' }).catch(() => false)
  if (!ok) return
  try {
    await request.put(`/api/system/mail-config/${row.id}/default`)
    ElMessage.success('已设为默认')
    load()
  } catch (e) { /* 拦截器已弹错误 */ }
}

async function toggle (row) {
  const body = { ...row }
  try {
    await request.put('/api/system/mail-config', body)
    ElMessage.success(row.enabled === 1 ? '已启用' : '已停用')
  } catch (e) {
    row.enabled = row.enabled === 1 ? 0 : 1   // 失败回滚视图
  }
}

function testSend (row) {
  testForm.configName = row.name
  testForm.configId = row.id
  testForm.to = ''
  testVisible.value = true
}

async function doTestSend () {
  if (!testForm.to) { ElMessage.warning('请输入接收邮箱'); return }
  testSending.value = true
  try {
    // 后端 test 返回 {success: bool, ...}
    const r = await request.post(`/api/system/mail-config/${testForm.configId}/test`, { to: testForm.to })
    if (r?.success) {
      ElMessage.success(`测试邮件已发送至 ${testForm.to}，请查收`)
      testVisible.value = false
    } else {
      ElMessage.error(`发送失败：${r?.error || '可能 SMTP 不通或配置错误'}`)
    }
  } catch (e) { /* 拦截器已弹错误 */ } finally { testSending.value = false }
}

async function del (row) {
  const ok = await ElMessageBox.confirm(`确认删除 ${row.name}？删除后该渠道无法发送邮件。`, '删除', { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' }).catch(() => false)
  if (!ok) return
  try {
    await request.delete(`/api/system/mail-config/${row.id}`)
    ElMessage.success('已删除')
    load()
  } catch (e) { /* 拦截器已弹错误 */ }
}

onMounted(load)
</script>

<style scoped>
.mail-config-page { padding: 20px; }
.page-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-head h2 { margin: 0; font-size: 20px; }
.password-mask { color: #999; font-family: Consolas, monospace; letter-spacing: 1px; }
</style>
