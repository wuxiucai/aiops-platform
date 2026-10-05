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
    <el-dialog v-model="createVisible" title="新建 Agent" width="500px">
      <el-form :model="createForm" label-width="100px">
        <el-form-item label="目标主机" required>
          <el-select v-model="createForm.targetId" style="width: 100%">
            <el-option v-for="t in targets" :key="t.id" :value="t.id" :label="`${t.name} (${t.ip})`"/>
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible=false">取消</el-button>
        <el-button type="primary" @click="create" :loading="creating">创建</el-button>
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
const loading = ref(false)
const creating = ref(false)
const createVisible = ref(false)
const installCmdVisible = ref(false)
const installCmd = ref('')

const createForm = reactive({ targetId: null })

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

async function load () {
  loading.value = true
  try {
    const r = await listAgents()
    const agents = r.data || []
    // 并发查 status
    const enriched = await Promise.all(agents.map(async (a) => {
      let online = false
      try {
        const s = await statusOf(a.targetId)
        online = s.data?.online === true
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
  // 弹窗打开前拉一遍 target，避免 targets 在初始 onMounted 未完成时为空
  await loadTargets()
  if (targets.value.length > 0) {
    createForm.targetId = targets.value[0].id
  }
  createVisible.value = true
}

async function create () {
  if (!createForm.targetId) return
  creating.value = true
  try {
    const r = await createAgentApi({ targetId: createForm.targetId })
    if (r.code === 200) {
      installCmd.value = r.data.installCommand
      installCmdVisible.value = true
      createVisible.value = false
      load()
    } else {
      ElMessage.error(r.msg || '创建失败')
    }
  } finally {
    creating.value = false
  }
}

async function toggle (row) {
  const r = await toggleAgent(row.id)
  if (r.code === 200) ElMessage.success(row.status === 1 ? '已启用' : '已停用')
  else ElMessage.error(r.msg || '失败')
  load()
}

async function refresh () {
  await load()
  ElMessage.success('已刷新')
}

function downloadJar (row) {
  window.open(`/api/agent/download/${row.id}`, '_blank')
}

function copyInstallCmd (row) {
  window.open(`/api/agent/download/${row.id}`, '_blank')
  copyText(`wget http://localhost:8080/api/agent/download/${row.id} -O aiops-agent.jar && nohup java -jar aiops-agent.jar --platform.url=http://localhost:8080 --agent.key=${row.agentKey || '<hidden>'} --target.id=${row.targetId} > aiops-agent.log 2>&1 &`)
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
