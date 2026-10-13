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

          <div style="display: flex; justify-content: space-between; align-items: center; padding: 4px 4px 8px 4px">
            <span style="font-weight: 500">模板</span>
            <el-button size="small" type="primary" plain @click="createTemplate">+ 新建模板</el-button>
          </div>

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
            <el-dropdown @command="createFromPreset" style="margin: 0 4px">
              <el-button size="small" type="warning" plain :loading="creatingPreset">一键模板</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="duty">值班总览（告警 + 事件）</el-dropdown-item>
                  <el-dropdown-item command="capacity">容量观察（主机负载曲线）</el-dropdown-item>
                  <el-dropdown-item command="postmortem">告警复盘（Top 告警 + 热模板）</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-tooltip content="联动后，曲线/Top告警类 widget 统一使用该时间范围（覆盖各自配置）" placement="top">
              <el-select v-model="globalHours" size="small" style="width: 130px" placeholder="时间联动: 关" clearable>
                <el-option :value="1" label="联动: 近 1 小时"/>
                <el-option :value="6" label="联动: 近 6 小时"/>
                <el-option :value="24" label="联动: 近 24 小时"/>
              </el-select>
            </el-tooltip>
            <el-button v-if="mode==='edit'" size="small" type="primary" @click="save" :loading="saving">保存布局</el-button>
            <el-button v-if="mode==='edit'" size="small" @click="addWidget">添加 Widget</el-button>
            <el-button size="small" type="success" @click="setDefault" :disabled="!current || current.isDefault === 1">设为默认</el-button>
          </div>
        </div>

        <el-card shadow="never" v-loading="loading">
          <!-- vue-grid-layout：拖拽 + 调整大小 -->
          <!-- 绝对定位 + 手动鼠标拖拽（稳定可靠） -->
          <div v-if="layout.length > 0" class="dash-grid-abs" :style="{ height: gridHeight + 'px' }">
            <div
              v-for="item in layout"
              :key="item.i"
              class="dash-cell-abs"
              :style="{
                left: `${item.x / 12 * 100}%`,
                top: `${item.y * 80}px`,
                width: `${item.w / 12 * 100}%`,
                height: `${item.h * 80}px`
              }"
            >
              <div class="widget-box">
                <div class="widget-head"
                     @mousedown.prevent="mode==='edit' && onHeadMouseDown(item, $event)">
                  <span class="widget-title">
                    <span v-if="mode==='edit'" class="drag-handle" title="按住拖到任意位置">⠿</span>
                    {{ getWidget(item.i)?.title || '未命名' }}
                  </span>
                  <el-button v-if="mode==='edit'" size="small" link type="danger" @click="removeWidget(item.i)">✕</el-button>
                </div>
                <div class="widget-body">
                  <WidgetRenderer :w="getWidget(item.i)" :global-hours="globalHours" />
                </div>
                <!-- 右下角缩放手柄 -->
                <div v-if="mode==='edit'" class="resize-handle"
                     @mousedown.prevent="onResizeMouseDown(item, $event)"></div>
              </div>
            </div>
          </div>
          <el-empty v-if="layout.length === 0 && !loading" description="无 widget，点击【添加 Widget】新建" />
        </el-card>
      </el-col>
    </el-row>

    <!-- Add widget dialog -->
    <el-dialog v-model="addVisible" title="添加 Widget" width="640px">
      <el-form :model="newWidget" label-width="100px">
        <el-form-item label="类型" required>
          <el-select v-model="newWidget.widgetType" style="width: 100%">
            <el-option value="stat_card"     label="stat_card — 单一大数字 (KPI 卡片)"/>
            <el-option value="line_chart"    label="line_chart — 时序折线"/>
            <el-option value="top_alert"     label="top_alert — Top 告警"/>
            <el-option value="top_template"  label="top_template — Top 日志模板"/>
            <el-option value="incident_list" label="incident_list — 进行中事件"/>
            <el-option value="metric_compare" label="metric_compare — 多指标对比"/>
          </el-select>
        </el-form-item>
        <el-form-item label="标题" required>
          <el-input v-model="newWidget.title" placeholder="如 CPU 使用率"/>
        </el-form-item>

        <!-- ========== stat_card 字段 ========== -->
        <template v-if="newWidget.widgetType === 'stat_card'">
          <el-form-item label="统计对象" required>
            <el-select v-model="newWidget.refType" style="width: 100%">
              <el-option value="alert_pending_count" label="待处理告警数 (24h pending)"/>
              <el-option value="incident_open_count" label="进行中故障数 (open)"/>
              <el-option value="log_anomaly_24h" label="日志异常数 (24h)"/>
              <el-option value="llm_call_24h" label="LLM 调用数 (24h)"/>
            </el-select>
          </el-form-item>
          <el-form-item label="监控对象（可选，留空为全部）">
            <el-select v-model="newWidget.targetId" clearable placeholder="全部对象">
              <el-option v-for="t in allTargets" :key="t.id" :value="t.id" :label="`${t.name} (${t.ip})`"/>
            </el-select>
          </el-form-item>
        </template>

        <!-- ========== line_chart / metric_compare 字段 ========== -->
        <template v-else-if="newWidget.widgetType === 'line_chart' || newWidget.widgetType === 'metric_compare'">
          <el-form-item label="监控对象" required>
            <el-select v-model="newWidget.targetId" style="width: 100%">
              <el-option v-for="t in allTargets" :key="t.id" :value="t.id" :label="`${t.name} (${t.ip})`"/>
            </el-select>
          </el-form-item>
          <el-form-item v-if="newWidget.widgetType === 'metric_compare'" label="指标(多选)" required>
            <el-select v-model="newWidget.metricKeys" multiple style="width: 100%">
              <el-option v-for="m in metricDefs" :key="m.metricKey" :value="m.metricKey" :label="`${m.metricName} (${m.metricKey})`"/>
            </el-select>
          </el-form-item>
          <el-form-item v-else label="指标" required>
            <el-select v-model="newWidget.metricKey" style="width: 100%">
              <el-option v-for="m in metricDefs" :key="m.metricKey" :value="m.metricKey" :label="`${m.metricName} (${m.metricKey})`"/>
            </el-select>
          </el-form-item>
          <el-form-item label="时间范围">
            <el-select v-model="newWidget.timeRangeHours">
              <el-option :value="1" label="1 小时"/>
              <el-option :value="6" label="6 小时"/>
              <el-option :value="24" label="24 小时"/>
              <el-option :value="72" label="3 天"/>
            </el-select>
          </el-form-item>
        </template>

        <!-- ========== top_alert / top_template / incident_list 字段 ========== -->
        <template v-else-if="newWidget.widgetType === 'top_alert' || newWidget.widgetType === 'top_template' || newWidget.widgetType === 'incident_list'">
          <el-form-item label="数量限制">
            <el-input-number v-model="newWidget.limit" :min="1" :max="20"/>
          </el-form-item>
          <!-- top_alert: targetId 可选过滤 -->
          <el-form-item v-if="newWidget.widgetType === 'top_alert'" label="监控对象（可选）">
            <el-select v-model="newWidget.targetId" clearable placeholder="全部对象">
              <el-option v-for="t in allTargets" :key="t.id" :value="t.id" :label="`${t.name} (${t.ip})`"/>
            </el-select>
          </el-form-item>
          <!-- top_template: service 可选过滤 -->
          <el-form-item v-if="newWidget.widgetType === 'top_template'" label="日志服务（可选）">
            <el-select v-model="newWidget.service" clearable placeholder="全部服务">
              <el-option v-for="s in logServices" :key="s" :value="s" :label="s"/>
            </el-select>
          </el-form-item>
          <!-- incident_list: targetId 可选过滤 -->
          <el-form-item v-if="newWidget.widgetType === 'incident_list'" label="监控对象（可选）">
            <el-select v-model="newWidget.targetId" clearable placeholder="全部对象">
              <el-option v-for="t in allTargets" :key="t.id" :value="t.id" :label="`${t.name} (${t.ip})`"/>
            </el-select>
          </el-form-item>
          <el-form-item v-if="newWidget.widgetType !== 'incident_list'" label="时间范围(h)">
            <el-select v-model="newWidget.timeRangeHours">
              <el-option :value="1" label="1 小时"/>
              <el-option :value="24" label="24 小时"/>
            </el-select>
          </el-form-item>
          <el-form-item v-else label="状态" required>
            <el-select v-model="newWidget.status">
              <el-option value="open" label="进行中"/>
              <el-option value="processing" label="处理中"/>
              <el-option value="resolved" label="已解决"/>
            </el-select>
          </el-form-item>
        </template>
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
const creatingPreset = ref(false)
const globalHours = ref(null)  // null=不联动；1/6/24=全局时间范围联动
const current = ref(null)
const layout = ref([])
const addVisible = ref(false)
const newWidget = reactive({
  widgetType: 'line_chart',
  title: '',
  refType: 'alert_pending_count',
  targetId: null,
  metricKey: '',
  metricKeys: [],
  timeRangeHours: 1,
  limit: 5,
  status: 'open',
  config: ''
})
const allTargets = ref([])
const logServices = ref([])
const metricDefs = ref([])

async function loadTargetsAndMetrics () {
  try {
    const t = await request.get('/api/monitor/target/page', { params: { current: 1, size: 100 } })
    allTargets.value = t.records || []
  } catch (e) { /* 静默 */ }
  try {
    const m = await request.get('/api/monitor/metric/definitions')
    metricDefs.value = Array.isArray(m) ? m : (m?.records || [])
  } catch (e) { /* 静默 */ }
  try {
    const s = await request.get('/api/dashboard-stat/log-services')
    logServices.value = Array.isArray(s) ? s : []
  } catch (e) { /* 静默 */ }
}

const allTemplates = ref([])
const groupNodes = ref([])
const ungroupedCount = ref(0)
const currentGroupId = ref(null)  // null=全部， 0=未归类， >0 = 某分组
const moveTargetGroup = ref(null)

const groupDialogVisible = ref(false)
const groupForm = ref({ id: null, name: '', parentId: 0 })

const widgets = computed(() => current.value?.widgets || [])

/* 网格容器高度：子项全是绝对定位，必须显式撑高，否则后面的行会被裁切/看起来重叠。
   高度 = 最深的 (y + h) * 单元高 80px + 底部留白 */
const gridHeight = computed(() => {
  if (!layout.value.length) return 600
  const maxBottom = Math.max(...layout.value.map(it => (it.y || 0) + (it.h || 4)))
  return maxBottom * 80 + 16
})

function getWidget (i) {
  // i 可能是 string （grid item) 或 number (widget id)
  return widgets.value.find(w => w.id === i || w.id === Number(i) || String(w.id) === i)
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
  console.log('[openTemplate] START id=', id, 'widgets=', data?.widgets, 'layoutConfig=', data?.layoutConfig)
  // widgets 兜底：后端返回可能是 string （在 layoutConfig 是 string 的情况下） — 转 array
  if (data && !Array.isArray(data.widgets)) {
    data.widgets = typeof data.widgets === 'string' ? JSON.parse(data.widgets || '[]') : []
  }
  current.value = data
  moveTargetGroup.value = data?.groupId ?? 0
  // layoutConfig 兜底：string 或 array 都接受
  let lc = []
  if (Array.isArray(data?.layoutConfig)) {
    lc = data.layoutConfig
  } else if (typeof data?.layoutConfig === 'string') {
    try { lc = JSON.parse(data.layoutConfig) } catch (e) { lc = [] }
  }
  // 自动补全 layoutConfig：widgets 在 db 但不在 layoutConfig 里的，附加默认位置
  // 注意统一用 String 比较：save() 历史版本把 widgetId 存成字符串，w.id 是数字，
  // 类型不一致会让每个 widget 都被误判"无位置"而重复追加（保存一次多一个窗口）
  const widgets = Array.isArray(data.widgets) ? data.widgets : []
  const layoutWidgetIds = new Set(lc.map(it => String(it.widgetId)))
  widgets.forEach((w, idx) => {
    if (!layoutWidgetIds.has(String(w.id))) {
      // 追加到 layout 末尾
      lc.push({
        widgetId: w.id,
        x: (idx % 2) * 6,
        y: Math.floor(idx / 2) * 4,
        w: 6,
        h: 4
      })
    }
  })
  // 去重：同一 widgetId 只保留第一个位置条目（清理历史脏数据里已积累的重复项）
  const seen = new Set()
  lc = lc.filter(it => {
    const k = String(it.widgetId)
    if (seen.has(k)) return false
    seen.add(k)
    return true
  })
  console.log('[openTemplate] lc=', JSON.stringify(lc), 'lc.length=', lc.length)
  layout.value = lc.map((it, idx) => ({
    i: String(it.widgetId != null ? it.widgetId : idx),
    x: it.x ?? (idx % 2) * 6,
    y: it.y ?? Math.floor(idx / 2) * 4,
    w: it.w ?? 6,
    h: it.h ?? 4,
    static: false
  }))
  console.log('[openTemplate] END layout=', layout.value.length, 'items')
}

async function createTemplate () {
  const { value } = await ElMessageBox.prompt('请输入新模板名称', '新建模板', {
    confirmButtonText: '创建',
    cancelButtonText: '取消',
    inputPlaceholder: '如：运维值班看板 / 主机监控大盘'
  }).catch(() => ({ value: null }))
  if (!value) return
  try {
    const r = await request.post('/api/dashboard/template', {
      name: value,
      layoutConfig: [],
      widgets: []
    })
    ElMessage.success('模板已创建，请添加 widget')
    // 立即打开新模板（不依赖 load()，因为 load 会优先打开 default 模板）
    const newId = r?.id
    if (newId) {
      await openTemplate(newId)
    }
    await load()
  } catch (e) { /* 拦截器已弹错 */ }
}

async function save () {
  if (!current.value?.id) return
  const payload = {
    name: current.value.name,
    // widgetId 归一化为数字（layout 的 i 是 String(widgetId)），
    // 避免与 openTemplate 的数字 id 比较不一致导致重开时重复追加窗口
    layoutConfig: layout.value.map(it => {
      const n = Number(it.i)
      return { widgetId: Number.isNaN(n) ? it.i : n, x: it.x, y: it.y, w: it.w, h: it.h }
    }),
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

/* ========== 一键模板：从预设场景生成完整大盘，不必从空白开始 ========== */
const PRESETS = {
  duty: {
    name: '值班总览',
    widgets: (H) => [
      { widgetType: 'stat_card', title: '待处理告警', config: { refType: 'alert_pending_count' }, sort: 0 },
      { widgetType: 'stat_card', title: '进行中事件', config: { refType: 'incident_open_count' }, sort: 1 },
      { widgetType: 'top_alert', title: 'Top 告警 (24h)', config: { limit: 8, timeRangeHours: 24 }, sort: 2 },
      { widgetType: 'incident_list', title: '开放事件', config: { status: 'open', limit: 8 }, sort: 3 }
    ],
    layout: [
      { idx: 0, x: 0, y: 0, w: 6, h: 2 },
      { idx: 1, x: 6, y: 0, w: 6, h: 2 },
      { idx: 2, x: 0, y: 2, w: 6, h: 4 },
      { idx: 3, x: 6, y: 2, w: 6, h: 4 }
    ]
  },
  capacity: {
    name: '容量观察',
    widgets: (H) => [
      { widgetType: 'metric_compare', title: '主机 CPU / 内存对比', config: { targetId: H, metricKeys: ['cpu.usage', 'mem.usage'], timeRangeHours: 6 }, sort: 0 },
      { widgetType: 'line_chart', title: 'CPU 使用率趋势', config: { targetId: H, metricKey: 'cpu.usage', timeRangeHours: 24 }, sort: 1 },
      { widgetType: 'line_chart', title: '内存使用率趋势', config: { targetId: H, metricKey: 'mem.usage', timeRangeHours: 24 }, sort: 2 }
    ],
    layout: [
      { idx: 0, x: 0, y: 0, w: 12, h: 5 },
      { idx: 1, x: 0, y: 5, w: 6, h: 5 },
      { idx: 2, x: 6, y: 5, w: 6, h: 5 }
    ]
  },
  postmortem: {
    name: '告警复盘',
    widgets: (H) => [
      { widgetType: 'top_alert', title: '24h 告警分布', config: { limit: 10, timeRangeHours: 24 }, sort: 0 },
      { widgetType: 'incident_list', title: '开放事件列表', config: { status: 'open', limit: 10 }, sort: 1 },
      { widgetType: 'top_template', title: '热点日志模板', config: { limit: 10 }, sort: 2 }
    ],
    layout: [
      { idx: 0, x: 0, y: 0, w: 6, h: 6 },
      { idx: 1, x: 6, y: 0, w: 6, h: 6 },
      { idx: 2, x: 0, y: 6, w: 12, h: 5 }
    ]
  }
}

async function createFromPreset (key) {
  const preset = PRESETS[key]
  if (!preset || creatingPreset.value) return
  creatingPreset.value = true
  try {
    // 预设里容量观察需要一台主机目标
    if (!allTargets.value.length) await loadTargetsAndMetrics()
    const host = allTargets.value.find(t => t.targetType === 'host')
    if (!host) { ElMessage.warning('请先在监控对象中创建主机目标'); return }

    // 1. 建空模板 → 2. 写入 widgets → 3. 按标题回取真实 widgetId → 4. 写入 layout
    const created = await request.post('/api/dashboard/template', { name: preset.name })
    const id = created?.id
    if (!id) { ElMessage.error('模板创建失败'); return }
    await saveTemplate(id, { name: preset.name, widgets: preset.widgets(host.id) })

    const refreshed = await getTemplate(id)
    const realWidgets = Array.isArray(refreshed?.widgets) ? refreshed.widgets : []
    const idByTitle = {}
    realWidgets.forEach(w => { idByTitle[w.title] = w.id })
    const layoutConfig = preset.layout
      .map(p => {
        const wid = idByTitle[realWidgets[p.idx]?.title]
        return wid != null ? { widgetId: wid, x: p.x, y: p.y, w: p.w, h: p.h } : null
      })
      .filter(Boolean)
    await saveTemplate(id, { layoutConfig })

    ElMessage.success(`已生成「${preset.name}」模板`)
    await loadTemplates()
    await openTemplate(id)
  } catch (e) { /* 拦截器已弹错 */ } finally {
    creatingPreset.value = false
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

/* ========== Mouse 拖拽（mousedown + mousemove + mouseup）========== */
const draggingItem = ref(null)
let dragStartX = 0
let dragStartY = 0
let dragStartCellX = 0
let dragStartCellY = 0

const resizingItem = ref(null)
let resizeStartX = 0
let resizeStartY = 0
let resizeStartW = 0
let resizeStartH = 0

function onHeadMouseDown (item, ev) {
  ev.preventDefault()
  ev.stopPropagation()
  draggingItem.value = item
  dragStartX = ev.clientX
  dragStartY = ev.clientY
  dragStartCellX = item.x
  dragStartCellY = item.y
  document.addEventListener('mousemove', onDocMouseMove)
  document.addEventListener('mouseup', onDocMouseUp, { once: true })
}

function onResizeMouseDown (item, ev) {
  ev.preventDefault()
  ev.stopPropagation()
  resizingItem.value = item
  resizeStartX = ev.clientX
  resizeStartY = ev.clientY
  resizeStartW = item.w
  resizeStartH = item.h
  document.addEventListener('mousemove', onDocMouseMove)
  document.addEventListener('mouseup', onDocMouseUp, { once: true })
}

function onDocMouseMove (ev) {
  const grid = document.querySelector('.dash-grid-abs')
  if (!grid) return
  const rect = grid.getBoundingClientRect()
  const cellW = rect.width / 12
  const cellH = 80

  if (draggingItem.value) {
    const dx = ev.clientX - dragStartX
    const dy = ev.clientY - dragStartY
    const cellsX = Math.round(dx / cellW)
    const cellsY = Math.round(dy / cellH)
    let newX = dragStartCellX + cellsX
    let newY = dragStartCellY + cellsY
    newX = Math.max(0, Math.min(12 - draggingItem.value.w, newX))
    newY = Math.max(0, newY)
    draggingItem.value.x = newX
    draggingItem.value.y = newY
  }

  if (resizingItem.value) {
    const dx = ev.clientX - resizeStartX
    const dy = ev.clientY - resizeStartY
    const cellsX = Math.round(dx / cellW)
    const cellsY = Math.round(dy / cellH)
    let newW = resizeStartW + cellsX
    let newH = resizeStartH + cellsY
    newW = Math.max(2, Math.min(12, newW))
    newH = Math.max(2, newH)
    if (resizingItem.value.x + newW > 12) newW = 12 - resizingItem.value.x
    resizingItem.value.w = newW
    resizingItem.value.h = newH
  }
}

function onDocMouseUp () {
  draggingItem.value = null
  resizingItem.value = null
  document.removeEventListener('mousemove', onDocMouseMove)
}
/* ========== /Mouse 拖拽 ========== */

/* ========== Widget 位置挪动（按钮方式，最稳定）========== */
/* ========== /Mouse 拖拽 ========== */

/* ========== /Drag & Drop ========== */

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
  if (!newWidget.title) {
    ElMessage.warning('标题必填')
    return
  }
  if (!current.value?.id) {
    ElMessage.warning('请先选中一个模板再添加 Widget')
    return
  }
  // 按 widgetType 拼 config JSON
  let configObj = {}
  if (newWidget.widgetType === 'stat_card') {
    configObj = { refType: newWidget.refType }
    if (newWidget.targetId) configObj.targetId = newWidget.targetId
  } else if (newWidget.widgetType === 'line_chart') {
    if (!newWidget.targetId || !newWidget.metricKey) { ElMessage.warning('选择监控对象与指标'); return }
    configObj = { targetId: newWidget.targetId, metricKey: newWidget.metricKey, timeRangeHours: newWidget.timeRangeHours }
  } else if (newWidget.widgetType === 'metric_compare') {
    if (!newWidget.targetId || !newWidget.metricKeys?.length) { ElMessage.warning('选择监控对象与指标'); return }
    configObj = { targetId: newWidget.targetId, metricKeys: newWidget.metricKeys, timeRangeHours: newWidget.timeRangeHours }
  } else if (newWidget.widgetType === 'top_alert') {
    configObj = { limit: newWidget.limit, timeRangeHours: newWidget.timeRangeHours }
    if (newWidget.targetId) configObj.targetId = newWidget.targetId
  } else if (newWidget.widgetType === 'top_template') {
    configObj = { limit: newWidget.limit, timeRangeHours: newWidget.timeRangeHours }
    if (newWidget.service) configObj.service = newWidget.service
  } else if (newWidget.widgetType === 'incident_list') {
    configObj = { limit: newWidget.limit, status: newWidget.status }
    if (newWidget.targetId) configObj.targetId = newWidget.targetId
  }
  // widgets 可能为 undefined，先兼容空数组
  const existingWidgets = current.value.widgets || []
  const payload = {
    widgets: [...existingWidgets, {
      widgetType: newWidget.widgetType,
      title: newWidget.title,
      config: JSON.stringify(configObj),
      sort: existingWidgets.length
    }],
    // 暂存现有 layout（新 widget 在响应里拿真实 id 后再 patch）
    layoutConfig: layout.value.map(it => ({ widgetId: it.i, x: it.x, y: it.y, w: it.w, h: it.h }))
  }
  try {
    await saveTemplate(current.value.id, payload)
    addVisible.value = false
    // 重新 getTemplate 取回后端真实 widget 数据（含 id) 与 layoutConfig
    const refreshed = await getTemplate(current.value.id)
    const realWidgets = Array.isArray(refreshed?.widgets) ? refreshed.widgets : []
    // 挑出新加的那个：sort === existingWidgets.length
    const newWidget = realWidgets.find(w => w.sort === existingWidgets.length)
    if (newWidget) {
      // 给新 widget 分配位置：排在最后，宽 6 列高 4 行
      const newLayoutItem = {
        i: String(newWidget.id),
        x: (realWidgets.length - 1) % 2 * 6,
        y: Math.floor((realWidgets.length - 1) / 2) * 4,
        w: 6,
        h: 4,
        static: false
      }
      layout.value = [...layout.value, newLayoutItem]
      // 持久化最终 layout_config（widgetId 统一为数字，避免重开时类型不一致误判）
      try {
        await saveTemplate(current.value.id, {
          widgets: realWidgets,
          layoutConfig: layout.value.map(it => {
            const n = Number(it.i)
            return { widgetId: Number.isNaN(n) ? it.i : n, x: it.x, y: it.y, w: it.w, h: it.h }
          })
        })
      } catch (e) { console.warn('layout save failed:', e) }
    }
    // 重置 newWidget
    newWidget.title = ''
    newWidget.config = ''
    newWidget.refType = 'alert_pending_count'
    newWidget.metricKey = ''
    newWidget.metricKeys = []
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

onMounted(() => { load(); loadTargetsAndMetrics() })
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
.widget-head { display: flex; justify-content: space-between; align-items: center; padding: 4px 8px; border-bottom: 1px solid #f0f0f0; font-size: 12px; user-select: none; -webkit-user-select: none; cursor: grab; }
.widget-head:active { cursor: grabbing; }
.widget-ops { display: flex; gap: 2px; align-items: center; }
.widget-ops .el-button { padding: 2px 4px !important; font-size: 12px !important; }
.widget-title { color: #303133; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; display: flex; align-items: center; gap: 6px; }
.drag-handle { color: #909399; font-size: 14px; letter-spacing: -2px; }
.widget-body { flex: 1; overflow: hidden; padding: 4px }

.dash-grid {
  display: grid;
  grid-template-columns: repeat(12, 1fr);
  grid-auto-rows: 80px;
  gap: 12px;
  padding: 12px;
}
.dash-cell { overflow: hidden }
.dash-grid-abs {
    position: relative;
    width: 100%;
    min-height: 600px;
  }
.dash-cell-abs {
    position: absolute;
    overflow: hidden;
}
.widget-box {
  display: flex;
  flex-direction: column;
  height: 100%;
  border: 1px solid #e4e7ed;
  border-radius: 3px;
  background: #fff;
  position: relative;
}
.resize-handle {
  position: absolute;
  width: 20px;
  height: 20px;
  bottom: 0;
  right: 0;
  cursor: se-resize;
  background: transparent;
  z-index: 4;
}
.resize-handle::after {
  content: "";
  position: absolute;
  right: 3px;
  bottom: 3px;
  width: 5px;
  height: 5px;
  border-right: 2px solid rgba(0,0,0,0.4);
  border-bottom: 2px solid rgba(0,0,0,0.4);
}

/* vue-grid-layout 必需样式 */
.vue-grid-layout {
    position: relative;
    transition: height 200ms ease;
    background: transparent;
}
.vue-grid-item {
    transition: all 200ms ease;
    transition-property: left, top, right;
    background: #fff;
    border: 1px solid #dcdfe6;
    border-radius: 4px;
    overflow: hidden;
    box-sizing: border-box;
}
.vue-grid-item.cssTransforms { transition-property: transform; }
.vue-grid-item.resizing { opacity: 0.9; z-index: 3; }
.vue-grid-item.static { background: #f5f7fa; }
.vue-grid-item.vue-grid-placeholder {
    background: #e6a23c;
    opacity: 0.2;
    transition-duration: 100ms;
    z-index: 2;
    user-select: none;
}
.vue-grid-item > .vue-resizable-handle {
    position: absolute;
    width: 20px;
    height: 20px;
    bottom: 0;
    right: 0;
    cursor: se-resize;
    background: transparent;
    z-index: 4;
}
.vue-grid-item > .vue-resizable-handle::after {
    content: "";
    position: absolute;
    right: 3px;
    bottom: 3px;
    width: 5px;
    height: 5px;
    border-right: 2px solid rgba(0, 0, 0, 0.4);
    border-bottom: 2px solid rgba(0, 0, 0, 0.4);
}
.vue-grid-item:not(.vue-grid-placeholder) {
    box-shadow: 0 1px 2px 0 rgba(0, 0, 0, 0.03);
}
</style>
