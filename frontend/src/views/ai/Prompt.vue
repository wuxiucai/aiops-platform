<template>
  <div class="prompt-page">
    <div class="page-head">
      <h2>提示词管理（7 场景）</h2>
      <div class="actions">
        <el-button @click="load" :loading="loading">刷新</el-button>
        <el-alert type="info" :closable="false" style="margin-left: 16px">
          · 保存 = version+1（下次 LLM 调用即可生效，老的 version 不变）
        </el-alert>
      </div>
    </div>

    <el-card shadow="never" v-loading="loading">
      <el-table :data="rows" border stripe>
        <el-table-column prop="id" label="ID" width="60"/>
        <el-table-column prop="sceneCode" label="场景码" width="160"/>
        <el-table-column prop="sceneName" label="场景名" width="180"/>
        <el-table-column label="status" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.enabled === 1" type="success" size="small">启用</el-tag>
            <el-tag v-else type="info" size="small">停用</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="version" label="Version" width="90"/>
        <el-table-column prop="updateTime" label="最后修改" width="180"/>
        <el-table-column label="预览" min-width="180">
          <template #default="{ row }">
            <el-button link size="small" @click="preview(row)">
              <el-icon><View/></el-icon> 查看内容
            </el-button>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" size="small" @click="edit(row)" v-if="hasPerm('llm:prompt:update')">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="rows.length === 0 && !loading" description="暂无提示词"/>
    </el-card>

    <!-- 预览弹窗（只读） -->
    <el-dialog v-model="previewVisible" :title="`【${previewRow?.sceneName || ''}】 提示词预览`" width="80%">
      <div v-if="previewRow" class="preview-grid">
        <section>
          <h4>System Prompt</h4>
          <pre class="prompt-pre">{{ previewRow.systemPrompt }}</pre>
        </section>
        <section>
          <h4>User Prompt Template</h4>
          <pre class="prompt-pre">{{ previewRow.userPromptTpl }}</pre>
        </section>
        <section v-if="previewRow.outputSchema">
          <h4>Output Schema（JSON Schema）</h4>
          <pre class="prompt-pre schema">{{ previewRow.outputSchema }}</pre>
        </section>
      </div>
    </el-dialog>

    <!-- 编辑弹窗 -->
    <el-dialog v-model="editVisible" :title="`编辑【${editRow?.sceneName || ''}】 v${editRow?.version}`" width="85%" destroy-on-close>
      <el-form v-if="editRow" :model="editRow" label-width="140px">
        <el-form-item label="场景码">
          <el-input v-model="editRow.sceneCode" disabled/>
        </el-form-item>
        <el-form-item label="场景名">
          <el-input v-model="editRow.sceneName" disabled/>
        </el-form-item>
        <el-form-item label="System Prompt" required>
          <el-input
            v-model="editRow.systemPrompt"
            type="textarea"
            :rows="4"
            placeholder="你扮演什么角色，用何种行为约束..."
          />
        </el-form-item>
        <el-form-item label="User Prompt 模板" required>
          <el-input
            v-model="editRow.userPromptTpl"
            type="textarea"
            :rows="8"
            placeholder="支持 ${metricList} / ${targetList} / ${question} / ${inputSummary} 等占位符..."
          />
        </el-form-item>
        <el-form-item label="Output Schema (JSON)">
          <el-input
            v-model="editRow.outputSchema"
            type="textarea"
            :rows="6"
            placeholder='{"type":"object","required":["summary"],"properties":{"summary":{"type":"string"}}}'
          />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="editRow.enabled" :active-value="1" :inactive-value="0"/>
        </el-form-item>
        <el-form-item>
          <el-alert type="warning" :closable="false">
            修改后 : 将自动 version+1。保存是 **DELETE-by-key** 不是 DELETE by row.
          </el-alert>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible=false">取消</el-button>
        <el-button type="primary" @click="save" :loading="saving">保存（version+1）</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { View } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { listPrompt, getPrompt, savePrompt } from '../../api/llm'
import { useUserStore } from '../../store/user'

const userStore = useUserStore()
const hasPerm = (code) => userStore.hasPerm(code)

const rows = ref([])
const loading = ref(false)
const saving = ref(false)

const previewVisible = ref(false)
const previewRow = ref(null)
const editVisible = ref(false)
const editRow = ref(null)

async function load () {
  loading.value = true
  try {
    const r = await listPrompt()
    rows.value = r.data || []
  } finally { loading.value = false }
}

async function preview (row) {
  const r = await getPrompt(row.id)
  previewRow.value = r.data
  previewVisible.value = true
}

async function edit (row) {
  const r = await getPrompt(row.id)
  editRow.value = r.data
  editVisible.value = true
}

async function save () {
  // 前置校验：outputSchema 必须是合法 JSON 对象（否则后端写入后再 put 会 500 且破坏场景链路）
  const schema = editRow.value.outputSchema
  if (schema != null && String(schema).trim() !== '') {
    try {
      JSON.parse(schema)
    } catch (e) {
      ElMessage.error(`Output Schema 不是合法 JSON：${e.message}`)
      return
    }
  }
  saving.value = true
  try {
    const r = await savePrompt(editRow.value)
    if (r.code === 200) {
      ElMessage.success(`保存成功，version ${r.data.newVersion}`)
      editVisible.value = false
      load()
    } else {
      ElMessage.error(r.msg || '保存失败')
    }
  } finally { saving.value = false }
}

onMounted(load)
</script>

<style scoped>
.prompt-page { padding: 20px; }
.page-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-head h2 { margin: 0; font-size: 20px; }
.actions { display: flex; align-items: center; }
.prompt-pre {
  font-family: Consolas, 'Courier New', monospace;
  font-size: 12px;
  background: #f5f7fa;
  padding: 12px;
  border-radius: 4px;
  max-height: 400px;
  overflow: auto;
  white-space: pre-wrap;
  word-wrap: break-word;
}
.prompt-pre.schema { background: #fcf6ec; }
.preview-grid section { margin-bottom: 20px; }
.preview-grid h4 { margin: 0 0 8px 0; color: #666; font-size: 13px; }
</style>
