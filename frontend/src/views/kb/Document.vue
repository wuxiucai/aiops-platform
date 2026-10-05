<template>
  <div>
    <el-card shadow="hover">
      <template #header>
        <div class="header-bar">
          <span>知识库 · 文档管理</span>
          <div>
            <el-input
              v-model="query.keyword"
              placeholder="按标题模糊查询"
              clearable
              style="width: 220px; margin-right: 8px"
              @keyup.enter="load"
            />
            <el-button type="primary" @click="load">查询</el-button>
            <el-button type="success" @click="openDialog()" v-if="hasPerm('kb:doc:list')">新建文档</el-button>
            <el-button @click="uploadVisible = true" v-if="hasPerm('kb:doc:list')">上传文档</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" border stripe v-loading="loading" empty-text="暂无文档">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
        <el-table-column prop="docType" label="类型" width="80">
          <template #default="{ row }">
            <el-tag size="small">{{ row.docType || 'txt' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="chunkCount" label="分块数" width="90" align="center" />
        <el-table-column prop="source" label="来源" width="180" show-overflow-tooltip />
        <el-table-column prop="embeddingStatus" label="嵌入" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.embeddingStatus === 'done' ? 'success' : 'info'">
              {{ row.embeddingStatus || 'none' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="updateTime" label="更新时间" width="170" />
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="viewChunks(row)">分块</el-button>
            <el-button link type="primary" @click="openDialog(row)" v-if="hasPerm('kb:doc:list')">编辑</el-button>
            <el-button link type="danger" @click="onDelete(row)" v-if="hasPerm('kb:doc:list')">删除</el-button>
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

    <!-- 新建/编辑 dialog -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑文档' : '新建文档'" width="720px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="标题" required>
          <el-input v-model="form.title" placeholder="如：order-service OOM 处理 SOP" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.docType" style="width: 140px">
            <el-option value="txt" label="txt" />
            <el-option value="md" label="md" />
          </el-select>
        </el-form-item>
        <el-form-item label="标签">
          <el-input v-model="form.tags" placeholder='JSON array, e.g. ["oom","sop"]' />
        </el-form-item>
        <el-form-item label="正文" required>
          <el-input v-model="form.content" type="textarea" :rows="12" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save" :loading="saving">保存</el-button>
      </template>
    </el-dialog>

    <!-- 上传 dialog -->
    <el-dialog v-model="uploadVisible" title="上传文档（.txt / .md）" width="520px">
      <el-form label-width="100px">
        <el-form-item label="标题">
          <el-input v-model="uploadForm.title" placeholder="留空则使用文件名" />
        </el-form-item>
        <el-form-item label="标签">
          <el-input v-model="uploadForm.tags" placeholder='JSON array or 留空' />
        </el-form-item>
        <el-form-item label="文件">
          <input type="file" accept=".txt,.md" @change="onFilePick" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="uploadVisible = false">取消</el-button>
        <el-button type="primary" @click="doUpload" :loading="uploading" :disabled="!uploadForm.file">上传</el-button>
      </template>
    </el-dialog>

    <!-- 分块查看 drawer -->
    <el-drawer v-model="chunksVisible" :title="`分块 · ${currentDoc?.title}`" size="60%">
      <el-table :data="chunkRows" border v-loading="chunksLoading">
        <el-table-column prop="chunkIndex" label="#" width="60" />
        <el-table-column prop="tokenCount" label="字符" width="80" align="center" />
        <el-table-column prop="content" label="内容">
          <template #default="{ row }">
            <pre class="chunk-pre">{{ row.content }}</pre>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>
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
const dialogVisible = ref(false)
const uploadVisible = ref(false)
const chunksVisible = ref(false)
const saving = ref(false)
const uploading = ref(false)
const chunksLoading = ref(false)

const query = reactive({ current: 1, size: 10, keyword: '' })
const form = ref({ id: null, title: '', docType: 'txt', tags: '', content: '' })
const uploadForm = reactive({ title: '', tags: '', file: null })
const chunkRows = ref([])
const currentDoc = ref(null)

async function load () {
  loading.value = true
  try {
    // 拦截器已解包 res.data 直接返回 data；此处 data = IPage 对象 {records, total}
    const r = await request.get('/api/kb/document', { params: query })
    rows.value = r?.records || []
    total.value = r?.total || 0
  } finally { loading.value = false }
}

function openDialog (row) {
  form.value = row
    ? { id: row.id, title: row.title, docType: row.docType || 'txt', tags: row.tags || '', content: row.content || '' }
    : { id: null, title: '', docType: 'txt', tags: '', content: '' }
  if (row && !row.content) {
    // 列表可能没返回 content；拉一次详情
    request.get(`/api/kb/document/${row.id}`).then(d => {
      form.value.content = d?.content || ''
    })
  }
  dialogVisible.value = true
}

async function save () {
  if (!form.value.title || !form.value.content) {
    ElMessage.warning('标题与正文必填')
    return
  }
  saving.value = true
  try {
    if (form.value.id) await request.put('/api/kb/document', form.value)
    else               await request.post('/api/kb/document', form.value)
    ElMessage.success('已保存')
    dialogVisible.value = false
    load()
  } catch (e) { /* 拦截器已弹错 */ } finally { saving.value = false }
}

async function onDelete (row) {
  try {
    await ElMessageBox.confirm(
      `确定删除文档 "${row.title}"？将同时删除其 ${row.chunkCount || 0} 个分块。`,
      '删除',
      { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' }
    )
  } catch { return }
  try {
    await request.delete(`/api/kb/document/${row.id}`)
    ElMessage.success('已删除')
    load()
  } catch (e) { /* 拦截器已弹错 */ }
}

async function viewChunks (row) {
  currentDoc.value = row
  chunksVisible.value = true
  chunksLoading.value = true
  try {
    const r = await request.get(`/api/kb/document/${row.id}/chunks`)
    chunkRows.value = r || []
  } finally { chunksLoading.value = false }
}

function onFilePick (e) {
  uploadForm.file = e.target.files?.[0] || null
}

async function doUpload () {
  if (!uploadForm.file) return
  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('file', uploadForm.file)
    if (uploadForm.title) fd.append('title', uploadForm.title)
    if (uploadForm.tags) fd.append('tags', uploadForm.tags)
    await request.post('/api/kb/document/upload', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
    ElMessage.success('上传成功')
    uploadVisible.value = false
    uploadForm.title = ''
    uploadForm.tags = ''
    uploadForm.file = null
    load()
  } catch (e) { /* 拦截器已弹错 */ } finally { uploading.value = false }
}

onMounted(load)
</script>

<style scoped>
.header-bar { display: flex; justify-content: space-between; align-items: center; }
.chunk-pre {
  white-space: pre-wrap;
  word-break: break-all;
  font-family: Consolas, monospace;
  font-size: 12px;
  margin: 0;
}
</style>
