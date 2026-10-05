<template>
  <div>
    <el-card shadow="hover">
      <template #header>
        <div class="header-bar">
          <span>知识库 · 故障案例</span>
          <div>
            <el-input
              v-model="query.keyword"
              placeholder="按标题模糊查询"
              clearable
              style="width: 220px; margin-right: 8px"
              @keyup.enter="load"
            />
            <el-button type="primary" @click="load">查询</el-button>
            <el-button type="success" @click="openDialog()" v-if="hasPerm('kb:case:list')">新建案例</el-button>
            <el-button @click="syncEmbeddings" :loading="syncing" v-if="hasPerm('kb:case:list')">同步嵌入</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" border stripe v-loading="loading" empty-text="暂无案例">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
        <el-table-column prop="tags" label="标签" width="160">
          <template #default="{ row }">
            <el-tag v-for="t in parseTags(row.tags)" :key="t" size="small" style="margin-right: 4px">{{ t }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="source" label="来源" width="140" show-overflow-tooltip />
        <el-table-column prop="embeddingStatus" label="嵌入" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.embeddingStatus === 'done' ? 'success' : 'warning'">
              {{ row.embeddingStatus || 'pending' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="occurredTime" label="发生时间" width="170" />
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="viewDetail(row)">查看</el-button>
            <el-button link type="primary" @click="openDialog(row)" v-if="hasPerm('kb:case:list')">编辑</el-button>
            <el-button link type="danger" @click="onDelete(row)" v-if="hasPerm('kb:case:list')">删除</el-button>
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

    <!-- 详情 drawer -->
    <el-drawer v-model="detailVisible" :title="detail?.title" size="60%">
      <div v-if="detail" class="detail-body">
        <div class="detail-row"><span class="lbl">标签</span>
          <el-tag v-for="t in parseTags(detail.tags)" :key="t" size="small" style="margin-right:4px">{{ t }}</el-tag>
        </div>
        <div class="detail-row"><span class="lbl">发生时间</span>{{ detail.occurredTime || '-' }}</div>
        <div class="detail-row"><span class="lbl">来源</span>{{ detail.source || 'manual' }}</div>
        <div class="detail-row"><span class="lbl">关联 incident</span>{{ detail.relatedIncidentId || '-' }}</div>
        <h4>症状 (symptom)</h4>
        <pre class="block">{{ detail.symptom || '-' }}</pre>
        <h4>根因 (root_cause)</h4>
        <pre class="block">{{ detail.rootCause || '-' }}</pre>
        <h4>处置方案 (solution)</h4>
        <pre class="block">{{ detail.solution || '-' }}</pre>
      </div>
    </el-drawer>

    <!-- 新建/编辑 dialog -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑案例' : '新建案例'" width="720px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="标题" required>
          <el-input v-model="form.title" />
        </el-form-item>
        <el-form-item label="标签">
          <el-input v-model="form.tags" placeholder='JSON array, e.g. ["oom","jvm"]' />
        </el-form-item>
        <el-form-item label="发生时间">
          <el-date-picker v-model="form.occurredTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" style="width:100%"/>
        </el-form-item>
        <el-form-item label="症状">
          <el-input v-model="form.symptom" type="textarea" :rows="4" />
        </el-form-item>
        <el-form-item label="根因">
          <el-input v-model="form.rootCause" type="textarea" :rows="4" />
        </el-form-item>
        <el-form-item label="处置">
          <el-input v-model="form.solution" type="textarea" :rows="4" />
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
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../../utils/request'
import { useUserStore } from '../../store/user'

const userStore = useUserStore()
const hasPerm = (code) => userStore.hasPerm(code)

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const syncing = ref(false)
const dialogVisible = ref(false)
const detailVisible = ref(false)
const detail = ref(null)
const form = ref({ id: null, title: '', tags: '', symptom: '', rootCause: '', solution: '', occurredTime: null })

const query = reactive({ current: 1, size: 10, keyword: '' })

function parseTags (json) {
  if (!json) return []
  try { return JSON.parse(json) || [] } catch { return [] }
}

async function load () {
  loading.value = true
  try {
    const r = await request.get('/api/kb/case', { params: query })
    rows.value = r?.records || []
    total.value = r?.total || 0
  } finally { loading.value = false }
}

function openDialog (row) {
  form.value = row
    ? { id: row.id, title: row.title, tags: row.tags || '', symptom: row.symptom || '', rootCause: row.rootCause || '', solution: row.solution || '', occurredTime: row.occurredTime }
    : { id: null, title: '', tags: '', symptom: '', rootCause: '', solution: '', occurredTime: null }
  dialogVisible.value = true
}

async function save () {
  if (!form.value.title) {
    ElMessage.warning('标题必填')
    return
  }
  saving.value = true
  try {
    if (form.value.id) await request.put('/api/kb/case', form.value)
    else               await request.post('/api/kb/case', form.value)
    ElMessage.success('已保存')
    dialogVisible.value = false
    load()
  } catch (e) { /* 拦截器已弹错 */ } finally { saving.value = false }
}

async function onDelete (row) {
  try {
    await ElMessageBox.confirm(`确定删除案例 "${row.title}"？`, '删除',
      { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' })
  } catch { return }
  try {
    await request.delete(`/api/kb/case/${row.id}`)
    ElMessage.success('已删除')
    load()
  } catch (e) { /* 拦截器已弹错 */ }
}

function viewDetail (row) {
  detail.value = row
  detailVisible.value = true
}

async function syncEmbeddings () {
  syncing.value = true
  try {
    const r = await request.post('/api/kb/case/sync-embeddings')
    ElMessage.success(`嵌入同步： updated=${r?.updated ?? 0} / failed=${r?.failed ?? 0}`)
    load()
  } catch (e) { /* 拦截器已弹错 */ } finally { syncing.value = false }
}

onMounted(load)
</script>

<style scoped>
.header-bar { display: flex; justify-content: space-between; align-items: center; }
.detail-body h4 { margin: 16px 0 8px; color: #4361ee; }
.detail-row { margin-bottom: 8px; }
.detail-row .lbl { display: inline-block; width: 100px; color: #909399; }
.block {
  background: #f5f7fa;
  padding: 10px;
  border-radius: 4px;
  font-size: 13px;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0 0 8px 0;
}
</style>
