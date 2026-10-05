<template>
  <div class="provider-page">
    <div class="page-head">
      <h2>AI 模型配置</h2>
      <el-button type="primary" @click="openForm(null)" v-if="hasPerm('llm:provider:add')">
        <el-icon><Plus /></el-icon> 新建 Provider
      </el-button>
    </div>

    <el-card shadow="never">
      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="id" label="ID" width="60"/>
        <el-table-column prop="name" label="名称" width="150"/>
        <el-table-column prop="providerType" label="类型" width="140">
          <template #default="{ row }">
            <el-tag :type="row.providerType === 'openai_compatible' ? 'success' : 'info'">
              {{ row.providerType }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="baseUrl" label="Base URL" min-width="220" show-overflow-tooltip/>
        <el-table-column prop="modelName" label="Chat Model" width="180 show-overflow-tooltip"/>
        <el-table-column prop="embeddingModel" label="Embedding" width="180">
          <template #default="{ row }">
            <span v-if="row.embeddingModel" class="model-text">{{ row.embeddingModel }}</span>
            <el-tag v-else type="info" size="small">不支持</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="API Key" width="180">
          <template #default="{ row }">
            <code class="api-key">{{ maskKey(row.apiKey) }}</code>
          </template>
        </el-table-column>
        <el-table-column label="默认" width="80" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.isDefault === 1" type="success" size="small">默认</el-tag>
            <el-button v-else link size="small" @click="setDefault(row)" v-if="hasPerm('llm:provider:update')">设为默认</el-button>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.status" :active-value="1" :inactive-value="0" @change="toggle(row)" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button link size="small" type="primary" @click="openForm(row)" v-if="hasPerm('llm:provider:update')">编辑</el-button>
            <el-button link size="small" @click="test(row)">测试连接</el-button>
            <el-button link size="small" type="danger" @click="del(row)" v-if="hasPerm('llm:provider:delete')">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-alert v-if="rows.length === 0 && !loading" type="info" show-icon
                title="暂无 Provider。点右上角新增。" style="margin-top: 8px"/>
    </el-card>

    <!-- 新增 / 编辑 弹窗 -->
    <el-dialog v-model="dialog" :title="form.id ? '编辑 Provider' : '新建 Provider'" width="600px">
      <el-form :model="form" label-width="130px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如 DeepSeek / SiliconFlow" :disabled="form.id>0"/>
        </el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="form.providerType" style="width: 100%">
            <el-option value="openai_compatible" label="openai_compatible (DeepSeek / 通义 / 智谱 / OpenAI)"/>
            <el-option value="ollama" label="ollama (本地)"/>
          </el-select>
        </el-form-item>
        <el-form-item label="Base URL" required>
          <el-input v-model="form.baseUrl" placeholder="如 https://api.deepseek.com 或 https://api.siliconflow.cn/v1"/>
        </el-form-item>
        <el-form-item label="Chat 模型名">
          <el-input v-model="form.modelName" placeholder="如 deepseek-chat"/>
        </el-form-item>
        <el-form-item label="Embedding 模型名">
          <el-input v-model="form.embeddingModel" placeholder="如 BAAI/bge-large-zh-v1.5（没有则留空）"/>
        </el-form-item>
        <el-form-item :label="form.id ? 'API Key（不改则留空）' : 'API Key'" :required="!form.id">
          <el-input v-model="form.apiKey" type="password" show-password placeholder="sk-..." autocomplete="new-password"/>
        </el-form-item>
        <el-form-item label="温度 (temperature)">
          <el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" :precision="2"/>
        </el-form-item>
        <el-form-item label="Max Tokens">
          <el-input-number v-model="form.maxTokens" :min="128" :max="8192" :step="256"/>
        </el-form-item>
        <el-form-item label="超时 (ms)">
          <el-input-number v-model="form.timeoutMs" :min="5000" :max="120000" :step="1000"/>
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
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listProvider, addProvider, updateProvider, deleteProvider, setDefaultProvider, testProvider } from '../../api/llm'
import { useUserStore } from '../../store/user'

const userStore = useUserStore()
const hasPerm = (code) => userStore.hasPerm(code)

const rows = ref([])
const loading = ref(false)
const saving = ref(false)
const dialog = ref(false)
const form = ref(defaultForm())

function defaultForm () {
  return {
    id: null, name: '', providerType: 'openai_compatible',
    baseUrl: '', modelName: '', embeddingModel: '', apiKey: '',
    temperature: 0.3, maxTokens: 2048, timeoutMs: 60000, status: 1, isDefault: 0, remark: ''
  }
}

/**
 * 脱敏 API key：
 *   - null / "" / "****" → "****"
 *   - 以 "sk-" 开头 → "sk-XXXX...XXXX"（保留头 3 尾 4）
 *   - 其他形式 → "...XXXX"（只保留尾 4，避免误以为任何 OpenAI 兼容 key 都以 sk- 开头）
 */
function maskKey (k) {
  if (!k || k === '****') return '****'
  const s = String(k)
  if (s.startsWith('sk-')) {
    return s.length > 8 ? s.substring(0, 3) + '****' + s.substring(s.length - 4) : 'sk-****'
  }
  return s.length > 4 ? '...' + s.substring(s.length - 4) : '...'
}

async function load () {
  loading.value = true
  try {
    // 拦截器 code===200 时已解包返回 res.data（这里是 List<LlmProvider> 数组）
    const r = await listProvider()
    rows.value = r || []
  } finally { loading.value = false }
}

function openForm (row) {
  if (row) {
    form.value = { ...row, apiKey: '' }  // apiKey 不回传明文
  } else {
    form.value = defaultForm()
  }
  dialog.value = true
}

async function save () {
  saving.value = true
  try {
    const body = { ...form.value }
    if (!body.apiKey) delete body.apiKey  // 不改不传
    // request.js 拦截器在 code===200 时已返回 res.data，失败会 reject
    // 所以这里走到下一个 tick 就说明成功，不需要再判断 r.code
    if (body.id) await updateProvider(body)
    else        await addProvider(body)
    ElMessage.success('保存成功')
    dialog.value = false
    load()
  } catch (e) {
    // 拦截器已经 ElMessage.error 过了，这里不再重复提示
  } finally { saving.value = false }
}

async function setDefault (row) {
  const ok = await ElMessageBox.confirm(`确定把 ${row.name} 设为默认 LLM Provider 吗？`, '设为默认', { type: 'warning' }).catch(() => false)
  if (!ok) return
  try {
    await setDefaultProvider(row.id)
    ElMessage.success('已设为默认')
    load()
  } catch (e) { /* 拦截器已提示 */ }
}

async function toggle (row) {
  try {
    await updateProvider({ id: row.id, status: row.status })
    ElMessage.success(row.status === 1 ? '已启用' : '已停用')
  } catch (e) {
    // 失败回滚视图
    row.status = row.status === 1 ? 0 : 1
  }
}

async function test (row) {
  ElMessage.info('测试中...')
  try {
    // request.js 拦截器已解包到 data 层；后端返回 Map：{ success: bool, error: string }
    const r = await testProvider(row.id)
    if (r?.success) {
      ElMessage.success(`${row.name} 连接成功`)
    } else {
      ElMessage.error(`${row.name} 测试失败：${r?.error || '未知错误'}`)
    }
  } catch (e) { /* 拦截器已提示 */ }
}

async function del (row) {
  const ok = await ElMessageBox.confirm(`确认删除 ${row.name}？删除后不可恢复，耗影响场景立即失效。`, '删除确认', { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' }).catch(() => false)
  if (!ok) return
  try {
    await deleteProvider(row.id)
    ElMessage.success('已删除')
    load()
  } catch (e) { /* 拦截器已提示 */ }
}

onMounted(load)
</script>

<style scoped>
.provider-page { padding: 20px; }
.page-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-head h2 { margin: 0; font-size: 20px; }
.api-key { font-family: Consolas, monospace; font-size: 12px; color: #666; }
.model-text { font-family: Consolas, monospace; font-size: 12px; }
</style>
