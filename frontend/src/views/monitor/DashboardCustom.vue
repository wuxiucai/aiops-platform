<template>
  <div class="dash-custom">
    <div class="head">
      <el-radio-group v-model="mode" size="small">
        <el-radio-button value="view">查看</el-radio-button>
        <el-radio-button value="edit">编辑</el-radio-button>
      </el-radio-group>
      <div class="actions">
        <el-button size="small" @click="load" :loading="loading">刷新</el-button>
        <el-button v-if="mode==='edit'" size="small" type="primary" @click="save" :loading="saving">保存布局</el-button>
        <el-button v-if="mode==='edit'" size="small" @click="addWidget">添加 Widget</el-button>
        <el-button size="small" type="success" @click="setDefault" :disabled="current?.isDefault === 1">设为默认</el-button>
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
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, watch, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { listTemplates, getTemplate, saveTemplate, saveDefault as apiSaveDefault } from '../../api/dashboard'
import WidgetRenderer from './WidgetRenderer.vue'
import { GridLayout, GridItem } from 'vue-grid-layout'
import 'vue-grid-layout/dist/style.css'

const mode = ref('view')
const loading = ref(false)
const saving = ref(false)
const current = ref(null)
const layout = ref([])
const addVisible = ref(false)
const newWidget = reactive({ widgetType: 'line_chart', title: '', config: '' })

const widgets = computed(() => current.value?.widgets || [])

function getWidget (i) {
  return widgets.value.find(w => w.id === i)
}

async function load () {
  loading.value = true
  try {
    const r = await listTemplates()
    const list = r.data || []
    if (list.length === 0) {
      layout.value = []
      current.value = null
      return
    }
    const tpl = list.find(t => t.isDefault === 1) || list[0]
    await openTemplate(tpl.id)
  } finally {
    loading.value = false
  }
}

async function openTemplate (id) {
  const r = await getTemplate(id)
  if (r.code === 200) {
    current.value = r.data
    // layout_config is JSON array of {widgetId,x,y,w,h}
    const lc = Array.isArray(r.data.layoutConfig) ? r.data.layoutConfig : []
    layout.value = lc.map((it, idx) => ({
      i: it.widgetId != null ? it.widgetId : idx,
      x: it.x ?? (idx % 2) * 6,
      y: it.y ?? Math.floor(idx / 2) * 4,
      w: it.w ?? 6,
      h: it.h ?? 4,
      static: false
    }))
  }
}

async function save () {
  if (!current.value?.id) return
  // convert layout back to layout_config
  const payload = {
    name: current.value.name,
    layoutConfig: layout.value.map((it, idx) => ({
      widgetId: it.i,
      x: it.x, y: it.y, w: it.w, h: it.h
    })),
    widgets: current.value.widgets
  }
  saving.value = true
  try {
    const r = await saveTemplate(current.value.id, payload)
    if (r.code === 200) {
      ElMessage.success('布局已保存')
      await load()
    } else {
      ElMessage.error(r.msg || '保存失败')
    }
  } finally {
    saving.value = false
  }
}

async function setDefault () {
  if (!current.value?.id) return
  const r = await apiSaveDefault(current.value.id)
  if (r.code === 200) {
    ElMessage.success('已设为默认')
    await load()
  } else {
    ElMessage.error(r.msg || '失败')
  }
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
  // ask backend to create widget
  const nextZ = (layout.value.reduce((m, it) => Math.max(m, it.y + it.h), 0) || 0)
  const payload = {
    widgets: [...current.value.widgets, {
      widgetType: newWidget.widgetType,
      title: newWidget.title,
      config: newWidget.config,
      sort: current.value.widgets.length
    }],
    layoutConfig: layout.value.map((it, idx) => ({ widgetId: it.i, x: it.x, y: it.y, w: it.w, h: it.h }))
  }
  const r = await saveTemplate(current.value.id, payload)
  if (r.code === 200) {
    addVisible.value = false
    await openTemplate(current.value.id)
  } else {
    ElMessage.error(r.msg || '添加失败')
  }
}

onMounted(load)
</script>

<style scoped>
.dash-custom { padding: 16px; }
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; gap: 8px }
.widget-box { display: flex; flex-direction: column; height: 100%; border: 1px solid #e4e7ed; border-radius: 3px; background: #fff }
.widget-head { display: flex; justify-content: space-between; align-items: center; padding: 4px 8px; border-bottom: 1px solid #f0f0f0; font-size: 12px }
.widget-title { color: #303133; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap }
.widget-body { flex: 1; overflow: hidden; padding: 4px }
</style>
