<template>
  <div class="agent-page">
    <div class="page-head">
      <h2>远程 Agent</h2>
      <el-button type="primary" @click="openCreateDialog" v-if="hasPerm('monitor:target:update')">
        <el-icon><Plus /></el-icon> 新建 Agent
      </el-button>
    </div>

    <el-card shadow="never" v-loading="loading">
      <el-table :data="rows" border stripe>
        <el-table-column prop="id" label="ID" width="60"/>
        <el-table-column label="Target" min-width="180">
          <template #default="{ row }">
            <strong>{{ row.targetName }}</strong>
            <div class="muted">{{ row.targetIp }}</div>
          </template>
        </el-table-column>
        <el-table-column label="在线" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.online ? 'success' : 'info'" size="small">
              {{ row.online ? '在线' : '离线' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="90" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.status" :active-value="1" :inactive-value="0" @change="toggle(row)" />
          </template>
        </el-table-column>
        <el-table-column prop="version" label="version" width="90"/>
        <el-table-column prop="lastHeartbeat" label="最近心跳" width="180"/>
        <el-table-column prop="lastMetricTime" label="最近上报" width="180"/>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="refresh(row)">刷新</el-button>
            <el-button size="small" type="primary" plain @click="downloadJar(row)">下载 jar</el-button>
            <el-button size="small" @click="copyInstallCmd(row)" v-if="hasPerm('monitor:target:update')">复制安装命令</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="rows.length === 0 && !loading" description="暂无 agent"/>
    </el-card>

    <!-- 新建 dialog -->
    <el-dialog v-model="createVisible" title="新建 Agent" width="540px">
      <el-form :model="createForm" label-width="100px">
        <el-form-item label="目标主机" required>
          <el-select v-model="createForm.targetId" style="width: 100%" @change="onTargetChange">
            <el-option v-for="t in targets" :key="t.id" :value="t.id" :label="`${t.name} (${t.ip})`"/>
            <el-option :value="NEW_TARGET_SENTINEL" label="+ 新建主机（如果尚未注册）">
              <span style="color:#4361ee; font-weight:500">+ 新建主机（如果尚未注册）</span>
            </el-option>
          </el-select>
        </el-form-item>

        <!-- 选 "+ 新建主机" 时展开的机器信息区 -->
        <template v-if="createForm.targetId === NEW_TARGET_SENTINEL">
          <el-divider content-position="left">新主机信息</el-divider>
          <el-form-item label="名称" required>
            <el-input v-model="newTargetForm.name" placeholder="如 prod-web-05 / 客户数据库-北京-2"/>
          </el-form-item>
          <el-form-item label="IP / 主机" required>
            <el-input v-model="newTargetForm.ip" placeholder="如 10.8.1.99 或 hostname"/>
          </el-form-item>
          <el-form-item label="类型">
            <el-select v-model="newTargetForm.targetType" style="width: 100%">
              <el-option value="host" label="主机 host"/>
              <el-option value="service" label="应用 service"/>
            </el-select>
          </el-form-item>
          <el-form-item label="OS">
            <el-input v-model="newTargetForm.os" placeholder="如 Linux / Windows 11 (可选)"/>
          </el-form-item>
          <el-form-item label="日志服务名">
            <el-input v-model="newTargetForm.logServiceName" placeholder="如 order-service (可选)"/>
          </el-form-item>
          <el-form-item label="所属分组">
            <el-select v-model="newTargetForm.groupId" clearable placeholder="不分组" style="width: 100%">
              <el-option :value="null" label="不分组"/>
              <el-option v-for="g in groups" :key="g.id" :value="g.id" :label="g.name"/>
            </el-select>
          </el-form-item>
          <el-form-item label="描述">
            <el-input v-model="newTargetForm.description" type="textarea" :rows="2" placeholder="选填"/>
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="createVisible=false">取消</el-button>
        <el-button type="primary" @click="create" :loading="creating">
          {{ createForm.targetId === NEW_TARGET_SENTINEL ? '注册主机并创建 Agent' : '创建 Agent' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 安装命令 dialog -->
    <el-dialog v-model="installCmdVisible" title="Agent 安装命令" width="700px">
      <pre class="install-cmd">{{ installCmd }}</pre>
      <template #footer>
        <el-button type="primary" @click="copyText(installCmd)">复制</el-button>
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
const targets = ref([])
const groups = ref([])
const loading = ref(false)
const creating = ref(false)
const createVisible = ref(false)
const installCmdVisible = ref(false)
const installCmd = ref('')

// 新建主机特殊 sentinel：选它时展开 name/ip/os 等新主机字段
const NEW_TARGET_SENTINEL = -1

const createForm = reactive({ targetId: null })
const newTargetForm = reactive({
  name: '', ip: '', targetType: 'host', os: '', logServiceName: '',
  groupId: null, description: ''
})

function resetNewTargetForm () {
  Object.assign(newTargetForm, {
    name: '', ip: '', targetType: 'host', os: '', logServiceName: '',
    groupId: null, description: ''
  })
}

function onTargetChange (v) {
  if (v === NEW_TARGET_SENTINEL) {
    resetNewTargetForm()
  }
}

function listAgents () {
  return request.get('/api/agent/list')
}
function toggleAgent (id) {
  return request.put(`/api/agent/${id}/toggle`)
}
function listTargets () {
  return request.get('/api/monitor/target/page?current=1&size=100')
}
function statusOf (targetId) {
  return request.get(`/api/agent/status/${targetId}`)
}
function createAgentApi (body) {
  return request.post('/api/agent/create', body)
}
function createWithTargetApi (body) {
  return request.post('/api/agent/create-with-target', body)
}
function listGroups () {
  return request.get('/api/monitor/group/list')
}

async function load () {
  loading.value = true
  try {
    // 拦截器已解包到 data 层：r 就是 agent 数组
    const r = await listAgents()
    const agents = r || []
    // 并发查 status
    const enriched = await Promise.all(agents.map(async (a) => {
      let online = false
      try {
        const s = await statusOf(a.targetId)
        online = s?.online === true
      } catch { /* offline */ }
      return { ...a, online }
    }))
    rows.value = enriched
  } finally {
    loading.value = false
  }
}

async function loadTargets () {
  try {
    const r = await listTargets()
    // axios interceptors 把 res.data 作为响应体格代收 （意味着 r = 全的代理结尾)
    // 所以直接访问 r.records，不喜 r.data.records
    targets.value = r?.records || []
    if (createForm.targetId === null && targets.value.length > 0) {
      createForm.targetId = targets.value[0].id
    }
  } catch (e) {
    console.error(e)
  }
}

async function openCreateDialog () {
  // 弹窗打开前拉一遍 target + 分组，避免 targets 在初始 onMounted 未完成时为空
  await Promise.all([loadTargets(), loadGroupsAction()])
  if (targets.value.length > 0 && createForm.targetId !== NEW_TARGET_SENTINEL) {
    createForm.targetId = targets.value[0].id
  }
  resetNewTargetForm()
  createVisible.value = true
}

async function loadGroupsAction () {
  try {
    const r = await listGroups()
    groups.value = r || []
  } catch (e) { /* ignore */ }
}

async function create () {
  creating.value = true
  try {
    let data
    if (createForm.targetId === NEW_TARGET_SENTINEL) {
      // 一站式：先创 target 再创 agent（事务）
      if (!newTargetForm.name || !newTargetForm.ip) {
        ElMessage.warning('新主机时名称与 IP 必填')
        creating.value = false
        return
      }
      data = await createWithTargetApi({
        name: newTargetForm.name,
        ip: newTargetForm.ip,
        targetType: newTargetForm.targetType,
        os: newTargetForm.os,
        logServiceName: newTargetForm.logServiceName,
        groupId: newTargetForm.groupId,
        description: newTargetForm.description
      })
    } else {
      if (!createForm.targetId) {
        creating.value = false
        return
      }
      data = await createAgentApi({ targetId: createForm.targetId })
    }
    installCmd.value = data?.installCommand || ''
    installCmdVisible.value = true
    createVisible.value = false
    load()
  } catch (e) { /* 拦截器已弹错 */ } finally {
    creating.value = false
  }
}

async function toggle (row) {
  try {
    await toggleAgent(row.id)
    ElMessage.success(row.status === 1 ? '已启用' : '已停用')
  } catch (e) { /* 拦截器已弹错 */ }
  load()
}

async function refresh () {
  await load()
  ElMessage.success('已刷新')
}

function downloadJar (row) {
  // 加 cache-buster 时间戳：之前浏览器把一次失败响应（1001 JSON 错误页）
  // 记成对该 URL 的"已浏览内容"，后续即使后端恢复也用缓存。
  // query 参数变化 → 视为新 URL → 一定 hit 网络。
  const url = `/api/agent/download/${row.id}?_t=${Date.now()}`
  // 用 <a download> 而不是 window.open，浏览器行为更稳定
  const a = document.createElement('a')
  a.href = url
  a.download = 'aiops-agent.jar'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}

function copyInstallCmd (row) {
  // 从后端 /api/agent/list 拿到完整 install_command（含正确 base-url 和 --agent.target-id）
  request.get(`/api/agent/install-command/${row.id}`).then(cmd => {
    copyText(typeof cmd === 'string' ? cmd : (cmd?.installCommand || ''))
  }).catch(() => ElMessage.warning('获取安装命令失败'))
}

function copyText (txt) {
  navigator.clipboard?.writeText(txt)
    .then(() => ElMessage.success('已复制'))
    .catch(() => ElMessage.info('剪贴板失败， 请手动复制'))
}

onMounted(() => {
  loadTargets()
  load()
  // 3s 轮询在线状态
  setInterval(load, 30000)
})
</script>

<style scoped>
.agent-page { padding: 20px; }
.page-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-head h2 { margin: 0; font-size: 20px; }
.muted { color: #999; font-size: 12px; }
.install-cmd {
  background: #f5f7fa;
  padding: 12px;
  border-radius: 4px;
  font-size: 13px;
  font-family: Consolas, monospace;
  white-space: pre-wrap;
  word-wrap: break-word;
}
</style>
