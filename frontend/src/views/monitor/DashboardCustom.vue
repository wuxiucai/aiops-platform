<template>
  <div class="dash-custom">
    <!-- S3: 左侧分组树 + 右侧大盘 -->
    <el-row :gutter="12">
      <el-col :span="5">
        <el-card shadow="never" class="left-panel">
          <template #header>
            <div class="left-head">
              <span>分组</span>
              <el-button size="small" link type="primary" @click="openGroupDialog(null)">+ 新建</el-button>
            </div>
          </template>

          <div class="group-item" :class="{active: currentGroupId === null}" @click="pickGroup(null)">
            <span>全部 ({{ allTemplates.length }})</span>
          </div>
          <div class="group-item" :class="{active: currentGroupId === 0}" @click="pickGroup(0)">
            <span>未归类 ({{ ungroupedCount }})</span>
          </div>
          <div
            v-for="g in flatGroups"
            :key="g.id"
            class="group-item"
            :class="{active: currentGroupId === g.id}"
            :style="{ paddingLeft: (12 + g._depth * 16) + 'px' }"
            @click="pickGroup(g.id)"
          >
            <span>{{ g.name }} ({{ g.templateCount }})</span>
            <span class="ops" @click.stop>
              <el-button size="small" link type="primary" @click="openGroupDialog(g)">改</el-button>
              <el-button size="small" link type="danger" @click="onDeleteGroup(g)">删</el-button>
            </span>
          </div>

          <el-divider style="margin: 10px 0"/>

          <div class="tpl-side-list">
            <div
              v-for="t in filteredTemplates"
              :key="t.id"
              class="tpl-item"
              :class="{active: current?.id === t.id}"
              @click="openTemplate(t.id)"
            >
              {{ t.name }}
              <el-tag v-if="t.isDefault === 1" size="small" type="success">默认</el-tag>
            </div>
            <el-empty v-if="filteredTemplates.length === 0" description="无模板" :image-size="60"/>
          </div>
        </el-card>
      </el-col>

      <el-col :span="19">
        <div class="head">
          <el-radio-group v-model="mode" size="small">
            <el-radio-button value="view">查看</el-radio-button>
            <el-radio-button value="edit">编辑</el-radio-button>
          </el-radio-group>
          <div class="actions">
            <el-select
              v-model="moveTargetGroup"
              size="small"
              placeholder="移动到分组"
              style="width: 160px"
              @change="moveCurrent"
              :disabled="!current"
            >
              <el-option :value="0" label="未归类"/>
              <el-option v-for="g in flatGroups" :key="g.id" :value="g.id" :label="g.name"/>
            </el-select>
            <el-button size="small" @click="load" :loading="loading">刷新</el-button>
            <el-button v-if="mode==='edit'" size="small" type="primary" @click="save" :loading="saving">保存布局</el-button>
            <el-button v-if="mode==='edit'" size="small" @click="addWidget">添加 Widget</el-button>
            <el-button size="small" type="success" @click="setDefault" :disabled="!current || current.isDefault === 1">设为默认</el-button>
          </div>
        </div>

        <el-card shadow="never" v-loading="loading">
          <grid-layout
            v-model:layout="layout"
            :col-num="12"
            :row-height="80"
            :is-draggable="mode==='edit'"
            :is-resizable="mode==='edit'"
            :vertical-compact="true"
            :use-css-transforms="true"
          >
            <grid-item
              v-for="item in layout" :key="item.i"
              :i="item.i" :x="item.x" :y="item.y" :w="item.w" :h="item.h"
              :min-w="2" :min-h="2"
            >
              <div class="widget-box">
                <div class="widget-head">
                  <span class="widget-title">{{ getWidget(item.i)?.title || '未命名' }}</span>
                  <el-button
                    v-if="mode==='edit'" size="small" link type="danger"
                    @click="removeWidget(item.i)">✕</el-button>
                </div>
                <div class="widget-body">
                  <WidgetRenderer :widget="getWidget(item.i)" />
                </div>
              </div>
            </grid-item>
          </grid-layout>

          <el-empty v-if="layout.length === 0 && !loading" description="无 widget，点击【添加 Widget】新建" />
        </el-card>
      </el-col>
    </el-row>

    <!-- Add widget dialog -->
    <el-dialog v-model="addVisible" title="添加 Widget" width="640px">
      <el-form :model="newWidget" label-width="100px">
        <el-form-item label="类型" required>
          <el-select v-model="newWidget.widgetType" style="width: 100%">
            <el-option value="stat_card"   label="stat_card — 单一大数字"/>
            <el-option value="line_chart"  label="line_chart — 时序折线"/>
            <el-option value="top_alert"   label="top_alert — Top 告警种类"/>
            <el-option value="top_template"label="top_template — Top 日志模板"/>
            <el-option value="incident_list" label="incident_list — 进行中事件列表"/>
            <el-option value="metric_compare" label="metric_compare — 多 metric 对比线"/>
          </el-select>
        </el-form-item>
        <el-form-item label="标题" required>
          <el-input v-model="newWidget.title" placeholder="如 CPU 使用率"/>
        </el-form-item>
        <el-form-item label="配置 JSON" required>
          <el-input v-model="newWidget.config" type="textarea" :rows="4"
            placeholder='如 {"refType":"alert_pending_count"} 或 {"targetId":1,"metricKey":"cpu.usage","timeRangeHours":1}'/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addVisible=false">取消</el-button>
        <el-button type="primary" @click="doAddWidget">添加</el-button>
      </template>
    </el-dialog>

    <!-- 新建/重命名 group dialog -->
    <el-dialog v-model="groupDialogVisible" :title="groupForm.id ? '重命名分组' : '新建分组'" width="420px">
      <el-form :model="groupForm" label-width="80px">
        <el-form-item label="名称" required>
          <el-input v-model="groupForm.name" />
        </el-form-item>
        <el-form-item label="父分组">
          <el-select v-model="groupForm.parentId" style="width: 100%">
            <el-option :value="0" label="（根）"/>
            <el-option v-for="g in flatGroups.filter(x => x.id !== groupForm.id)" :key="g.id" :value="g.id" :label="g.name"/>
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="groupDialogVisible=false">取消</el-button>
        <el-button type="primary" @click="saveGroup">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listTemplates, getTemplate, saveTemplate, saveDefault as apiSaveDefault } from '../../api/dashboard'
import request from '../../utils/request'
import WidgetRenderer from './WidgetRenderer.vue'
import { GridLayout, GridItem } from 'vue-grid-layout'
import { useRoute, useRouter } from 'vue-router'
import './dashboard-grid.css'

const route = useRoute()
const router = useRouter()

const mode = ref('view')
const loading = ref(false)
const saving = ref(false)
const current = ref(null)
const layout = ref([])
const addVisible = ref(false)
const newWidget = reactive({ widgetType: 'line_chart', title: '', config: '' })

const allTemplates = ref([])
const groupNodes = ref([])
const ungroupedCount = ref(0)
const currentGroupId = ref(null)  // null=全部， 0=未归类， >0 = 某分组
const moveTargetGroup = ref(null)

const groupDialogVisible = ref(false)
const groupForm = ref({ id: null, name: '', parentId: 0 })

const widgets = computed(() => current.value?.widgets || [])

function getWidget (i) {
  return widgets.value.find(w => w.id === i)
}

/* 把平铺 node 列表转有序树形 （深度优先 + _depth)，便于 el-tree 风格平铺渲染 */
const flatGroups = computed(() => {
  const nodes = groupNodes.value || []
  const byParent = new Map()
  nodes.forEach(n => {
    const list = byParent.get(n.parentId) || []
    list.push(n)
    byParent.set(n.parentId, list)
  })
  const out = []
  function walk (pid, depth) {
    (byParent.get(pid) || []).forEach(n => {
      out.push({ ...n, _depth: depth })
      walk(n.id, depth + 1)
    })
  }
  walk(0, 0)
  return out
})

const filteredTemplates = computed(() => {
  if (currentGroupId.value === null) return allTemplates.value
  if (currentGroupId.value === 0)    return allTemplates.value.filter(t => !t.groupId)
  return allTemplates.value.filter(t => t.groupId === currentGroupId.value)
})

function pickGroup (gid) {
  currentGroupId.value = gid
  /* 同步到 URL ?group= */
  const q = { ...route.query }
  if (gid === null) delete q.group
  else q.group = String(gid)
  router.replace({ query: q })
}

/* 从 URL 读 ?group= */
watch(() => route.query.group, (g) => {
  if (g === undefined) currentGroupId.value = null
  else currentGroupId.value = Number(g)
}, { immediate: true })

async function loadGroups () {
  try {
    const r = await request.get('/api/dashboard/group/tree')
    groupNodes.value = r?.nodes || []
    ungroupedCount.value = r?.ungroupedCount || 0
  } catch (e) { /* 静默 */ }
}

async function loadTemplates () {
  const list = await listTemplates()
  allTemplates.value = list || []
}

async function load () {
  loading.value = true
  try {
    await Promise.all([loadGroups(), loadTemplates()])
    if (allTemplates.value.length === 0) {
      layout.value = []
      current.value = null
      return
    }
    /* 优先打开当前分组内的第一个，否则全局默认 */
    const inGroup = filteredTemplates.value
    const tpl = inGroup.find(t => t.isDefault === 1)
      || inGroup[0]
      || allTemplates.value.find(t => t.isDefault === 1)
      || allTemplates.value[0]
    if (tpl) await openTemplate(tpl.id)
  } finally {
    loading.value = false
  }
}

async function openTemplate (id) {
  if (!id) return
  const data = await getTemplate(id)
  current.value = data
  moveTargetGroup.value = data?.groupId ?? 0
  const lc = Array.isArray(data?.layoutConfig) ? data.layoutConfig : []
  layout.value = lc.map((it, idx) => ({
    i: it.widgetId != null ? it.widgetId : idx,
    x: it.x ?? (idx % 2) * 6,
    y: it.y ?? Math.floor(idx / 2) * 4,
    w: it.w ?? 6,
    h: it.h ?? 4,
    static: false
  }))
}

async function save () {
  if (!current.value?.id) return
  const payload = {
    name: current.value.name,
    layoutConfig: layout.value.map(it => ({ widgetId: it.i, x: it.x, y: it.y, w: it.w, h: it.h })),
    widgets: current.value.widgets
  }
  saving.value = true
  try {
    await saveTemplate(current.value.id, payload)
    ElMessage.success('布局已保存')
    await load()
  } catch (e) { /* 拦截器已弹错 */ } finally {
    saving.value = false
  }
}

async function setDefault () {
  if (!current.value?.id) return
  try {
    await apiSaveDefault(current.value.id)
    ElMessage.success('已设为默认')
    await load()
  } catch (e) { /* 拦截器已弹错 */ }
}

async function moveCurrent (gid) {
  if (!current.value?.id) return
  try {
    await request.put(`/api/dashboard/template/${current.value.id}/move`, { groupId: gid === 0 ? null : gid })
    ElMessage.success('已移动')
    current.value.groupId = gid === 0 ? null : gid
    await loadTemplates()
    await loadGroups()
  } catch (e) { /* 拦截器已弹错 */ }
}

function removeWidget (i) {
  const idx = layout.value.findIndex(it => it.i === i)
  if (idx >= 0) layout.value.splice(idx, 1)
}

function addWidget () {
  if (!current.value?.id) {
    ElMessage.warning('请先打开或新建一个模板')
    return
  }
  newWidget.title = ''
  newWidget.config = ''
  addVisible.value = true
}

async function doAddWidget () {
  if (!newWidget.title || !newWidget.config) {
    ElMessage.warning('标题与配置必填')
    return
  }
  const payload = {
    widgets: [...current.value.widgets, {
      widgetType: newWidget.widgetType,
      title: newWidget.title,
      config: newWidget.config,
      sort: current.value.widgets.length
    }],
    layoutConfig: layout.value.map(it => ({ widgetId: it.i, x: it.x, y: it.y, w: it.w, h: it.h }))
  }
  try {
    await saveTemplate(current.value.id, payload)
    addVisible.value = false
    await openTemplate(current.value.id)
  } catch (e) { /* 拦截器已弹错 */ }
}

/* ============== 分组管理 ============== */
function openGroupDialog (g) {
  groupForm.value = g
    ? { id: g.id, name: g.name, parentId: g.parentId ?? 0 }
    : { id: null, name: '', parentId: 0 }
  groupDialogVisible.value = true
}

async function saveGroup () {
  if (!groupForm.value.name) {
    ElMessage.warning('名称必填')
    return
  }
  try {
    if (groupForm.value.id) await request.put('/api/dashboard/group', groupForm.value)
    else                    await request.post('/api/dashboard/group', groupForm.value)
    ElMessage.success('已保存')
    groupDialogVisible.value = false
    await loadGroups()
    await loadTemplates()
  } catch (e) { /* 拦截器已弹错 */ }
}

async function onDeleteGroup (g) {
  try {
    await ElMessageBox.confirm(
      `确定删除分组"${g.name}"？其下 ${g.templateCount} 个模板将移入未归类。`,
      '删除分组',
      { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' }
    )
  } catch { return }
  try {
    await request.delete(`/api/dashboard/group/${g.id}`)
    ElMessage.success('已删除')
    if (currentGroupId.value === g.id) currentGroupId.value = null
    await loadGroups()
    await loadTemplates()
  } catch (e) { /* 拦截器已弹错 */ }
}

onMounted(load)
</script>

<style scoped>
.dash-custom { padding: 16px; }
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; gap: 8px; flex-wrap: wrap }
.left-panel { min-height: 600px }
.left-head { display: flex; justify-content: space-between; align-items: center }
.group-item {
  display: flex; justify-content: space-between; align-items: center;
  padding: 4px 12px; cursor: pointer; user-select: none;
  font-size: 13px;
  border-radius: 4px;
}
.group-item:hover { background: #f0f2f5; }
.group-item.active { background: #4361ee; color: #fff; }
.group-item .ops { opacity: 0; transition: opacity .15s }
.group-item:hover .ops { opacity: 1 }
.group-item.active .ops :deep(.el-button) { color: #fff }
.tpl-side-list .tpl-item {
  padding: 6px 10px;
  margin: 2px 0;
  cursor: pointer;
  font-size: 13px;
  border-radius: 4px;
  display: flex; justify-content: space-between; align-items: center;
}
.tpl-side-list .tpl-item:hover { background: #f5f7fa }
.tpl-side-list .tpl-item.active { background: #ecf3ff; color: #4361ee; font-weight: 500; }
.widget-box { display: flex; flex-direction: column; height: 100%; border: 1px solid #e4e7ed; border-radius: 3px; background: #fff }
.widget-head { display: flex; justify-content: space-between; align-items: center; padding: 4px 8px; border-bottom: 1px solid #f0f0f0; font-size: 12px }
.widget-title { color: #303133; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap }
.widget-body { flex: 1; overflow: hidden; padding: 4px }
</style>
