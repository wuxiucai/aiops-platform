<template>
  <div class="target-page">
    <!-- 左侧分组树：分组可展开/折叠，下面列该分组的 target -->
    <aside class="group-tree">
      <div class="tree-head">
        <span class="tree-title">分组</span>
        <el-button link size="small" @click="groupManageVisible = true" title="管理分组">
          <el-icon><Setting /></el-icon>
        </el-button>
      </div>
      <div class="tree-body">
        <!-- 全部：固定分组，相当于默认根 -->
        <div
          class="tree-item group-node"
          :class="{ active: currentGroupId === 0 }"
          @click="onSelectGroup(0)"
        >
          <el-icon class="tree-icon"><Folder /></el-icon>
          <span class="tree-label">全部</span>
          <span class="tree-count">{{ targets.length }}</span>
        </div>

        <!-- 每个分组：可展开/折叠，下面列该分组的 target -->
        <div v-for="g in groups" :key="g.id" class="group-block">
          <div
            class="tree-item group-node"
            :class="{ active: currentGroupId === g.id && currentTargetId == null }"
            @click="onToggleGroupExpand(g.id)"
          >
            <el-icon class="tree-arrow" :class="{ expanded: expandedGroups.has(g.id) }">
              <ArrowRight />
            </el-icon>
            <el-icon class="tree-icon"><FolderOpened v-if="expandedGroups.has(g.id)"/><Folder v-else/></el-icon>
            <span class="tree-label">{{ g.name }}</span>
            <span class="tree-count">{{ countByGroup(g.id) }}</span>
          </div>
          <!-- 展开后显示该分组下的 target 列表 -->
          <transition name="expand">
            <div v-show="expandedGroups.has(g.id)" class="target-list">
              <div
                v-for="t in targetsByGroup(g.id)" :key="t.id"
                class="tree-item target-node"
                :class="{ active: currentTargetId === t.id }"
                @click="onSelectTarget(t.id)"
              >
                <el-icon class="tree-icon target-type-icon" :class="`type-${t.targetType}`">
                  <Monitor v-if="t.targetType === 'host'"/>
                  <Connection v-else-if="t.targetType === 'service'"/>
                  <DataLine v-else/>
                </el-icon>
                <span class="tree-label" :title="t.name">{{ t.name }}</span>
                <span class="target-ip">{{ t.ip }}</span>
              </div>
              <div v-if="targetsByGroup(g.id).length === 0" class="tree-empty">(空)</div>
            </div>
          </transition>
        </div>

        <!-- 未分组 -->
        <div class="group-block">
          <div
            class="tree-item group-node"
            :class="{ active: currentGroupId === -1 && currentTargetId == null }"
            @click="onToggleGroupExpand(-1)"
          >
            <el-icon class="tree-arrow" :class="{ expanded: expandedGroups.has(-1) }">
              <ArrowRight />
            </el-icon>
            <el-icon class="tree-icon"><DocumentRemove /></el-icon>
            <span class="tree-label">未分组</span>
            <span class="tree-count">{{ countByGroup(null) }}</span>
          </div>
          <transition name="expand">
            <div v-show="expandedGroups.has(-1)" class="target-list">
              <div
                v-for="t in targetsByGroup(null)" :key="t.id"
                class="tree-item target-node"
                :class="{ active: currentTargetId === t.id }"
                @click="onSelectTarget(t.id)"
              >
                <el-icon class="tree-icon target-type-icon" :class="`type-${t.targetType}`">
                  <Monitor v-if="t.targetType === 'host'"/>
                  <Connection v-else-if="t.targetType === 'service'"/>
                  <DataLine v-else/>
                </el-icon>
                <span class="tree-label" :title="t.name">{{ t.name }}</span>
                <span class="target-ip">{{ t.ip }}</span>
              </div>
              <div v-if="targetsByGroup(null).length === 0" class="tree-empty">(空)</div>
            </div>
          </transition>
        </div>
      </div>
    </aside>

    <!-- 右侧主区域 -->
    <div class="target-main">
      <el-card shadow="hover">
        <template #header>
          <div class="header-bar">
            <span>
              <template v-if="currentTarget">{{ currentTarget.name }}</template>
              <template v-else-if="currentGroupId === 0">全部监控对象</template>
              <template v-else-if="currentGroupId === -1">未分组</template>
              <template v-else>{{ groups.find(g => g.id === currentGroupId)?.name || '监控对象' }}</template>
            </span>
            <div style="display:flex; gap:8px; align-items:center">
              <RefreshSelector v-model="refreshMs" @change="onRefreshChange" />
              <el-button type="primary" size="small" @click="openCreateDialog" v-if="hasPerm('monitor:target:add')" icon="Plus">新建监控对象</el-button>
            </div>
          </div>
        </template>

        <!-- 当前对象基本信息 + 操作 -->
        <el-empty v-if="!currentTarget" description="当前分组下没有监控对象" :image-size="80"/>
        <el-descriptions :column="4" border v-else>
          <el-descriptions-item label="名称">{{ currentTarget.name }}</el-descriptions-item>
          <el-descriptions-item label="类型">
            <el-tag :type="currentTarget.targetType === 'host' ? 'primary' : 'success'">
              {{ currentTarget.targetType }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="地址">{{ currentTarget.ip }}{{ currentTarget.port ? ':' + currentTarget.port : '' }}</el-descriptions-item>
          <el-descriptions-item label="日志服务名">{{ currentTarget.logServiceName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="所属分组">
            <el-tag v-if="groupOf(currentTarget)" size="small" type="info">{{ groupOf(currentTarget) }}</el-tag>
            <span v-else style="color:#909399; font-size:12px">未分组</span>
          </el-descriptions-item>
          <el-descriptions-item label="操作" :span="4">
            <el-button size="small" type="primary" plain @click="openEditDialog" v-if="hasPerm('monitor:target:update')">编辑</el-button>
            <el-button size="small" type="danger" plain @click="onDelete" v-if="hasPerm('monitor:target:delete')">删除</el-button>
          </el-descriptions-item>
        </el-descriptions>
      </el-card>

      <!-- 单机画像：指标画像 / Agent 信息 / 关联告警 -->
      <el-card shadow="hover" style="margin-top: 16px" v-if="currentTarget">
        <el-tabs v-model="activeTab" @tab-change="onTabChange">
          <el-tab-pane label="指标画像" name="metrics">
            <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:10px; gap:12px; flex-wrap:wrap">
              <el-checkbox-group v-model="selectedMetrics" size="small" @change="loadTrend">
                <el-checkbox-button v-for="k in metricOptionsFor(currentTarget)" :key="k" :value="k">
                  {{ METRIC_LABEL[k] || k }}
                </el-checkbox-button>
              </el-checkbox-group>
              <div style="display:flex; align-items:center; gap:8px">
                <span style="color:#909399; font-size:12px">时间范围（精确到秒）：</span>
                <el-date-picker
                  v-model="customTimeRange"
                  type="datetimerange"
                  range-separator="至"
                  start-placeholder="2026-10-01 00:00:00"
                  end-placeholder="2026-10-02 23:59:59"
                  format="YYYY-MM-DD HH:mm:ss"
                  value-format="YYYY-MM-DD HH:mm:ss"
                  :default-time="[new Date(0,0,0,0,0,0), new Date(0,0,0,23,59,59)]"
                  style="width: 380px"
                  size="small"
                  clearable
                  @change="onCustomTimeChange"
                />
                <el-button size="small" @click="resetTimeRange">重置为近 1 小时</el-button>
              </div>
            </div>
            <BaseChart :option="trendOption" height="360px" v-loading="chartLoading" />
          </el-tab-pane>

          <el-tab-pane label="Agent 信息" name="agent">
            <el-descriptions :column="3" border>
              <el-descriptions-item label="在线状态">
                <el-tag :type="agentOnline ? 'success' : 'info'" size="small">{{ agentOnline ? '在线' : '离线' }}</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="最近心跳">{{ currentTarget.lastHeartbeat || '-' }}</el-descriptions-item>
              <el-descriptions-item label="操作系统">{{ currentTarget.os || '-' }}</el-descriptions-item>
              <el-descriptions-item label="地址">{{ currentTarget.ip }}{{ currentTarget.port ? ':' + currentTarget.port : '' }}</el-descriptions-item>
              <el-descriptions-item label="目标 URL">{{ currentTarget.targetUrl || '-' }}</el-descriptions-item>
              <el-descriptions-item label="Agent 安装包">
                <a :href="`/api/agent/download/${currentTarget.id}`" target="_blank" style="color:#4361ee">下载 aiops-agent.jar</a>
              </el-descriptions-item>
            </el-descriptions>
            <div class="agent-tip">
              Agent 以 X-Agent-Key 鉴权上报心跳（默认 30s 一次），最近心跳在 2 分钟内判定为在线；
              上线后「监控对象」列表与大盘健康矩阵的在线状态会自动更新。
            </div>
          </el-tab-pane>

          <el-tab-pane :label="`关联告警${alertTotal > 0 ? ' (' + alertTotal + ')' : ''}`" name="alerts">
            <el-table :data="alertRows" size="small" stripe v-loading="alertLoading">
              <el-table-column prop="level" label="级别" width="100">
                <template #default="{ row }">
                  <el-tag :type="row.level === 'CRITICAL' ? 'danger' : (row.level === 'WARN' ? 'warning' : 'info')" size="small">
                    {{ row.level }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="title" label="告警内容" min-width="260" show-overflow-tooltip/>
              <el-table-column prop="status" label="状态" width="110"/>
              <el-table-column prop="lastTriggerTime" label="最近触发" width="170"/>
              <el-table-column prop="triggerCount" label="触发次数" width="90" align="right"/>
            </el-table>
            <el-empty v-if="!alertLoading && alertRows.length === 0" description="该对象暂无告警记录" :image-size="60"/>
          </el-tab-pane>
        </el-tabs>
      </el-card>
    </div><!-- /.target-main -->

    <!-- 编辑监控对象 dialog -->
    <el-dialog v-model="editVisible" title="编辑监控对象" width="520px">
      <el-form :model="editForm" label-width="110px">
        <el-form-item label="名称" required>
          <el-input v-model="editForm.name"/>
        </el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="editForm.targetType" style="width: 100%" :disabled="true"/>
        </el-form-item>
        <el-form-item label="IP / 主机" required>
          <el-input v-model="editForm.ip"/>
        </el-form-item>
        <el-form-item label="OS">
          <el-input v-model="editForm.os"/>
        </el-form-item>
        <el-form-item label="日志服务名">
          <el-input v-model="editForm.logServiceName"/>
        </el-form-item>
        <el-form-item label="所属分组">
          <el-select v-model="editForm.groupId" clearable placeholder="不分组" style="width: 100%">
            <el-option :value="null" label="不分组"/>
            <el-option v-for="g in groups" :key="g.id" :value="g.id" :label="g.name"/>
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="editForm.description" type="textarea" :rows="2"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible=false">取消</el-button>
        <el-button type="primary" @click="onEdit" :loading="editing">保存修改</el-button>
      </template>
    </el-dialog>

    <!-- 分组管理 dialog（融合分组特有功能：增删改） -->
    <el-dialog v-model="groupManageVisible" title="分组管理" width="640px">
      <div style="display:flex; justify-content:flex-end; margin-bottom: 12px">
        <el-button size="small" type="primary" @click="openGroupForm(null)">+ 新建分组</el-button>
      </div>
      <el-table :data="groups" border stripe size="small">
        <el-table-column prop="id" label="ID" width="60"/>
        <el-table-column prop="name" label="名称" min-width="140"/>
        <el-table-column prop="description" label="描述" min-width="180"/>
        <el-table-column prop="sort" label="排序" width="70"/>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link size="small" type="primary" @click="openGroupForm(row)">编辑</el-button>
            <el-button link size="small" type="danger" @click="onDeleteGroup(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 分组编辑 dialog -->
    <el-dialog v-model="groupFormVisible" :title="groupForm.id ? '编辑分组' : '新建分组'" width="420px">
      <el-form :model="groupForm" label-width="80px">
        <el-form-item label="名称" required>
          <el-input v-model="groupForm.name" placeholder="如 生产集群 / 演示微服务"/>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="groupForm.description" placeholder="选填"/>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="groupForm.sort" :min="0" :max="999"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="groupFormVisible=false">取消</el-button>
        <el-button type="primary" @click="saveGroup" :loading="groupSaving">保存</el-button>
      </template>
    </el-dialog>

    <!-- 新建监控对象 dialog -->
    <el-dialog v-model="createVisible" title="新建监控对象" width="520px">
      <el-form :model="createForm" label-width="110px">
        <el-form-item label="名称" required>
          <el-input v-model="createForm.name" placeholder="如 prod-web-01 / 客户数据库-北京-1"/>
        </el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="createForm.targetType" style="width: 100%">
            <el-option value="host" label="主机 host"/>
            <el-option value="service" label="应用 service"/>
          </el-select>
        </el-form-item>
        <el-form-item label="IP / 主机" required>
          <el-input v-model="createForm.ip" placeholder="如 10.8.1.52 或 hostname"/>
        </el-form-item>
        <el-form-item label="OS">
          <el-input v-model="createForm.os" placeholder="如 Linux / Windows 11"/>
        </el-form-item>
        <el-form-item label="日志服务名">
          <el-input v-model="createForm.logServiceName" placeholder="如 order-service / payment-service（可选）"/>
        </el-form-item>
        <el-form-item label="所属分组">
          <el-select v-model="createForm.groupId" clearable placeholder="不分组" style="width: 100%">
            <el-option :value="null" label="不分组"/>
            <el-option v-for="g in groups" :key="g.id" :value="g.id" :label="g.name"/>
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="createForm.description" type="textarea" :rows="2" placeholder="选填"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible=false">取消</el-button>
        <el-button type="primary" @click="onCreate" :loading="creating">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, reactive, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Plus, Setting, Folder, FolderOpened, DocumentRemove, ArrowRight, Monitor, Connection, DataLine } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import BaseChart from '../../components/BaseChart.vue'
import RefreshSelector from '../../components/RefreshSelector.vue'
import { listTarget, queryMetric } from '../../api/monitor'
import { getRecordPage } from '../../api/alert'
import request from '../../utils/request'
import { useUserStore } from '../../store/user'
import { minutesAgo, nowString } from '../../utils/time'

const route = useRoute()

const userStore = useUserStore()
const hasPerm = (code) => userStore.hasPerm(code)

const targets = ref([])
const currentTargetId = ref(1)
const trendOption = ref({})
const customTimeRange = ref(null)  // [startTime, endTime] "yyyy-MM-dd HH:mm:ss"
const chartLoading = ref(false)
let timer = null

// ============ 分组（融合监控分组页到监控对象页） ============
const groups = ref([])             // 所有 monitor_group 记录
const currentGroupId = ref(0)      // 0=全部, -1=未分组, >0=具体分组 id
const groupManageVisible = ref(false)
const groupFormVisible = ref(false)
const groupSaving = ref(false)
const groupForm = ref({ id: null, name: '', description: '', sort: 0 })

// 分组展开状态：用 Set 避免每次 render 重建对象。-1 = 未分组
const expandedGroups = ref(new Set())

// 当前分组下要显示的 target（前端过滤，monitor_target 表只有个位数，无需后端过滤）
const displayTargets = computed(() => {
  if (currentGroupId.value === 0) return targets.value
  if (currentGroupId.value === -1) return targets.value.filter(t => t.groupId == null)
  return targets.value.filter(t => t.groupId === currentGroupId.value)
})

// 按 groupId 取该分组下 target；gid=null 表示未分组
function targetsByGroup (gid) {
  if (gid == null) return targets.value.filter(t => t.groupId == null)
  return targets.value.filter(t => t.groupId === gid)
}

async function loadGroups () {
  try {
    const r = await request.get('/api/monitor/group/list')
    groups.value = r || []
    // 默认全部展开
    expandedGroups.value = new Set([...(r || []).map(g => g.id), -1])
  } catch (e) { /* ignore */ }
}

function onToggleGroupExpand (gid) {
  // 用了 Set，需要新建触发响应式
  const next = new Set(expandedGroups.value)
  if (next.has(gid)) next.delete(gid)
  else next.add(gid)
  expandedGroups.value = next
  // 同时将该分组作为筛选条件 + 取消当前 target 选中（看整个分组的概览）
  currentGroupId.value = gid
  currentTargetId.value = null
  trendOption.value = {}
}

function onGroupChange () {
  // 切换分组后，当前选中的 target 可能不在 displayTargets 里，回退到第一个
  const list = displayTargets.value
  if (list.length === 0) {
    currentTargetId.value = null
    trendOption.value = {}
    return
  }
  const still = list.find(t => t.id === currentTargetId.value)
  currentTargetId.value = still ? still.id : list[0].id
  loadTrend()
}

// 从树选中 target：重置指标选择 + 刷新趋势图；告警页签打开时同步刷新
function onSelectTarget (id) {
  currentTargetId.value = id
  selectedMetrics.value = defaultMetricsFor(currentTarget.value)
  loadTrend()
  if (activeTab.value === 'alerts') loadAlerts()
}

function openGroupForm (row) {
  groupForm.value = row
    ? { id: row.id, name: row.name, description: row.description || '', sort: row.sort || 0 }
    : { id: null, name: '', description: '', sort: 0 }
  groupFormVisible.value = true
}

async function saveGroup () {
  if (!groupForm.value.name) {
    ElMessage.warning('名称必填')
    return
  }
  groupSaving.value = true
  try {
    if (groupForm.value.id) await request.put('/api/monitor/group', groupForm.value)
    else                    await request.post('/api/monitor/group', groupForm.value)
    ElMessage.success('保存成功')
    groupFormVisible.value = false
    await loadGroups()
  } catch (e) { /* 拦截器已弹错误 */ } finally { groupSaving.value = false }
}

async function onDeleteGroup (row) {
  try {
    await ElMessageBox.confirm(
      `确定删除分组"${row.name}"？该分组下的 target 不会被删除，会被设为"未分组"。`,
      '删除分组',
      { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' }
    )
  } catch { return }
  try {
    await request.delete(`/api/monitor/group/${row.id}`)
    ElMessage.success('已删除')
    await loadGroups()
    await loadTargets()  // 重新拿 target 的 groupId（后端删除时应会置 null）
  } catch (e) { /* 拦截器已弹错误 */ }
}

// 自动刷新间隔（毫秒），0=暂停
const REFRESH_KEY = 'aiops_target_refresh_ms'
const refreshMs = ref(Number(localStorage.getItem(REFRESH_KEY)) || 30000)

function startTimer () {
  if (timer) { clearInterval(timer); timer = null }
  if (refreshMs.value > 0) {
    timer = setInterval(loadTrend, refreshMs.value)
  }
}
function onRefreshChange (ms) {
  localStorage.setItem(REFRESH_KEY, String(ms))
  startTimer()
}

function onCustomTimeChange (val) {
  // el-date-picker datetimerange 返回 [start, end] 字符串数组
  // 立即重新加载趋势图（带用户选定时间范围）
  if (Array.isArray(val) && val.length === 2) {
    loadTrend()
  }
}

function resetTimeRange () {
  customTimeRange.value = null
  loadTrend()
}

/* 新建 dialog 表单与状态 */
const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive({
  name: '', targetType: 'host', ip: '', os: '', logServiceName: '', description: '', groupId: null
})

/* 编辑 dialog 表单与状态 */
const editVisible = ref(false)
const editing = ref(false)
const editForm = reactive({
  id: null, name: '', targetType: 'host', ip: '', os: '', logServiceName: '', description: '', groupId: null
})

function openEditDialog () {
  if (!currentTarget.value) return
  Object.assign(editForm, {
    id: currentTarget.value.id,
    name: currentTarget.value.name,
    targetType: currentTarget.value.targetType,
    ip: currentTarget.value.ip,
    os: currentTarget.value.os || '',
    logServiceName: currentTarget.value.logServiceName || '',
    description: currentTarget.value.description || '',
    groupId: currentTarget.value.groupId ?? null
  })
  editVisible.value = true
}

async function onEdit () {
  if (!editForm.name || !editForm.ip) {
    ElMessage.warning('名称和 IP 必填')
    return
  }
  editing.value = true
  try {
    const body = {
      id: editForm.id,
      name: editForm.name,
      targetType: editForm.targetType,
      ip: editForm.ip,
      os: editForm.os,
      logServiceName: editForm.logServiceName,
      description: editForm.description,
      groupId: editForm.groupId
    }
    // 拦截器在 code=200 时直接 resolve(data)；非 200 已经 ElMessage 弹错 + reject
    await request.put('/api/monitor/target', body)
    ElMessage.success('已保存')
    editVisible.value = false
    await loadTargets()
  } catch (e) {
    /* 拦截器已弹错误 */
  } finally {
    editing.value = false
  }
}

async function onDelete () {
  if (!currentTarget.value) return
  const targetName = currentTarget.value.name
  try {
    await ElMessageBox.confirm(
      `确定删除监控对象 "${targetName}"？删除后其 metric_data / collect_task 数据不会清除（保留历史），但会持续上报.`,
      '删除监控对象',
      { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' }
    )
  } catch { return }
  try {
    await request.delete(`/api/monitor/target/${currentTarget.value.id}`)
    ElMessage.success(`已删除 ${targetName}`)
    await loadTargets()
  } catch (e) {
    /* 拦截器已弹错误 */
  }
}

function openCreateDialog () {
  Object.assign(createForm, {
    name: '', targetType: 'host', ip: '', os: '', logServiceName: '', description: '',
    // 新建时默认放到当前选中的分组下；在"全部"或"未分组"时为 null
    groupId: currentGroupId.value > 0 ? currentGroupId.value : null
  })
  createVisible.value = true
}

async function onCreate () {
  if (!createForm.name || !createForm.ip) {
    ElMessage.warning('名称和 IP 必填')
    return
  }
  creating.value = true
  try {
    const body = {
      name: createForm.name,
      targetType: createForm.targetType,
      ip: createForm.ip,
      os: createForm.os,
      logServiceName: createForm.logServiceName,
      description: createForm.description,
      groupId: createForm.groupId,   // 默认在 openCreateDialog 已预设为当前分组，用户可改
      status: 1
    }
    await request.post('/api/monitor/target', body)
    ElMessage.success('创建成功')
    createVisible.value = false
    const createdName = createForm.name
    const prevFirst = targets.value[0]?.id
    await loadTargets()
    if (targets.value.length > 0) {
      const nextId = targets.value.find(t => t.name === createdName)?.id ?? prevFirst
      currentTargetId.value = nextId
      onTargetChange()
    }
  } catch (e) {
    /* 拦截器已弹错误 */
  } finally {
    creating.value = false
  }
}

const currentTarget = computed(() => targets.value.find(t => t.id === currentTargetId.value))

// 当前 target 所属分组名（找不到时返回 ''）
function groupOf (t) {
  if (!t || t.groupId == null) return ''
  return groups.value.find(g => g.id === t.groupId)?.name || ''
}

// 每个分组下的 target 数量（用于左侧 tree 计数徽标）；null 表示未分组
function countByGroup (gid) {
  if (gid == null) return targets.value.filter(t => t.groupId == null).length
  return targets.value.filter(t => t.groupId === gid).length
}

function onSelectGroup (gid) {
  currentGroupId.value = gid
  onGroupChange()
}

/** 画像 Tab 可选指标（按任务书 v2 §2.5 / M2 审查点选）+ 显示名 */
const METRIC_LABEL = {
  'cpu.usage': 'CPU 使用率',
  'mem.usage': '内存使用率',
  'disk.usage': '磁盘使用率',
  'net.conn.count': '网络连接数',
  'jvm.heap.usage': 'JVM 堆使用率',
  'app.rt.avg': '平均响应时间',
  'app.qps': 'QPS',
  'jvm.thread.count': 'JVM 线程数'
}
function metricOptionsFor (t) {
  if (t?.targetType === 'host') return ['cpu.usage', 'mem.usage', 'disk.usage', 'net.conn.count']
  if (t?.targetType === 'prometheus') return ['cpu.usage', 'mem.usage']
  return ['jvm.heap.usage', 'app.rt.avg', 'app.qps', 'jvm.thread.count']
}
function defaultMetricsFor (t) {
  if (t?.targetType === 'host') return ['cpu.usage', 'mem.usage']
  if (t?.targetType === 'prometheus') return ['cpu.usage', 'mem.usage']
  return ['jvm.heap.usage', 'app.rt.avg']
}

/* ============ 单机画像 tabs ============ */
const activeTab = ref('metrics')
const selectedMetrics = ref(['cpu.usage', 'mem.usage'])
const alertRows = ref([])
const alertTotal = ref(0)
const alertLoading = ref(false)

/** Agent 在线判定：与后端 AgentController 一致，最近心跳 2 分钟内 */
const agentOnline = computed(() => {
  const t = currentTarget.value
  if (!t?.lastHeartbeat) return false
  return (Date.now() - new Date(t.lastHeartbeat.replace(' ', 'T')).getTime()) < 2 * 60_000
})

async function loadAlerts () {
  const t = currentTarget.value
  if (!t) { alertRows.value = []; alertTotal.value = 0; return }
  alertLoading.value = true
  try {
    const p = await getRecordPage({ current: 1, size: 10, targetId: t.id })
    alertRows.value = p.records || []
    alertTotal.value = p.total || 0
  } catch (e) { /* 拦截器已弹错 */ } finally {
    alertLoading.value = false
  }
}

function onTabChange (name) {
  if (name === 'alerts') loadAlerts()
}

async function loadTargets() {
  targets.value = await listTarget()
  if (targets.value.length) {
    // 支持从监控大盘健康矩阵下钻：?targetId=x 自动定位（无效 id 回退第一台）
    const wanted = Number(route.query.targetId)
    const hit = wanted && targets.value.find(t => t.id === wanted)
    currentTargetId.value = hit ? wanted : targets.value[0].id
    selectedMetrics.value = defaultMetricsFor(currentTarget.value)
    loadTrend()
  }
}

async function loadTrend() {
  const t = currentTarget.value
  if (!t) return
  chartLoading.value = true
  try {
    // 用户选了自定义时间范围就用，否则用默认近 60 分钟
    let startTime = minutesAgo(60)
    let endTime = nowString()
    if (Array.isArray(customTimeRange.value) && customTimeRange.value.length === 2 && customTimeRange.value[0] && customTimeRange.value[1]) {
      startTime = customTimeRange.value[0]
      endTime = customTimeRange.value[1]
    }
    // 按用户选定时间范围挑选聚合粒度：
    // 短窗口（≤2h）→ 10s 精度折线；中（≤24h）→ 1m；长 → 1h
    let step = '1m'
    if (Array.isArray(customTimeRange.value) && customTimeRange.value.length === 2 && customTimeRange.value[0] && customTimeRange.value[1]) {
      const durationMs = new Date(customTimeRange.value[1]).getTime() - new Date(customTimeRange.value[0]).getTime()
      if (durationMs <= 2 * 3600_000) step = '10s'
      else if (durationMs <= 24 * 3600_000) step = '1m'
      else step = '1h'
    } else {
      // 默认近 1 小时，10s 精度
      step = '10s'
    }
    const data = await queryMetric({
      targetIds: [t.id],
      metricKeys: selectedMetrics.value.length ? selectedMetrics.value : defaultMetricsFor(t),
      startTime,
      endTime,
      aggregation: 'avg',
      step
    })
    const series = {}
    data.forEach(row => {
      if (!series[row.metricKey]) series[row.metricKey] = []
      series[row.metricKey].push([row.bucketTime, row.value])
    })
    trendOption.value = {
      tooltip: { trigger: 'axis' },
      legend: { data: Object.keys(series) },
      xAxis: { type: 'time' },
      yAxis: { type: 'value' },
      series: Object.keys(series).map(k => ({
        name: k,
        type: 'line',
        smooth: true,
        showSymbol: false,
        data: series[k]
      }))
    }
  } finally {
    chartLoading.value = false
  }
}

function onTargetChange() {
  loadTrend()
}

onMounted(() => {
  loadGroups()
  loadTargets()
  startTimer()
})

onBeforeUnmount(() => { if (timer) clearInterval(timer) })
</script>

<style scoped>
.header-bar { display: flex; justify-content: space-between; align-items: center }

/* Agent 信息 tab 说明文字 */
.agent-tip {
  margin-top: 12px;
  font-size: 12px;
  color: #98a1b3;
  background: #f7f9fc;
  border: 1px solid #e9edf4;
  border-radius: 8px;
  padding: 8px 12px;
}

/* ============ 左树右内容布局 ============ */
.target-page {
  display: flex;
  gap: 12px;
  align-items: stretch;
  height: 100%;
}
.group-tree {
  width: 220px;
  flex-shrink: 0;
  background: #fff;
  border-radius: 8px;
  border: 1px solid #eef1f6;
  box-shadow: 0 1px 3px rgba(21,32,71,.05);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.tree-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 14px;
  border-bottom: 1px solid #eef1f6;
  background: #fafbfd;
}
.tree-title { font-weight: 600; font-size: 13px; color: #2b3245; }
.tree-body {
  flex: 1;
  overflow-y: auto;
  padding: 6px 4px;
}
.tree-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  margin: 2px 6px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  color: #2b3245;
  transition: background .15s;
  user-select: none;
}
.tree-item:hover { background: #f4f6fb; }
.tree-item.active {
  background: linear-gradient(90deg, #eef1fe 0%, #dfe6ff 100%);
  color: #4361ee;
  font-weight: 600;
}
.tree-icon { font-size: 14px; flex-shrink: 0; color: #97a1b5; }
.tree-item.active .tree-icon { color: #4361ee; }
.tree-label {
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.tree-count {
  font-size: 11px;
  color: #97a1b5;
  background: #f4f6fb;
  padding: 1px 6px;
  border-radius: 10px;
}
.tree-item.active .tree-count {
  background: #4361ee;
  color: #fff;
}

/* ============ 树节点（分组 + target） ============ */
.group-block { margin: 2px 0; }
.group-node { padding-left: 6px; }
.tree-arrow {
  font-size: 12px;
  color: #97a1b5;
  transition: transform .18s;
  flex-shrink: 0;
}
.tree-arrow.expanded { transform: rotate(90deg); }

.target-list {
  padding-left: 22px;    /* 缩进：让 target 在分组下视觉从属 */
  margin: 0;
}
.target-node {
  padding: 6px 10px;
  margin: 2px 6px;
  font-size: 12.5px;
}
.target-node:hover { background: #f4f6fb; }
.target-node.active {
  background: linear-gradient(90deg, #eef1fe 0%, #dfe6ff 100%);
  color: #2b3245;
  font-weight: 500;
}
.target-type-icon { font-size: 13px; }
.target-type-icon.type-host { color: #67c23a; }
.target-type-icon.type-service { color: #4361ee; }
.target-type-icon.type-prometheus { color: #e6a23c; }
.target-ip {
  font-size: 11px;
  color: #97a1b5;
  font-family: Consolas, monospace;
}

.tree-empty {
  padding: 6px 10px 6px 32px;
  color: #c0c4cc;
  font-size: 12px;
}

/* 展开/折叠动画 */
.expand-enter-active, .expand-leave-active {
  transition: opacity .18s, max-height .22s ease-out;
  overflow: hidden;
}
.expand-enter-from, .expand-leave-to { opacity: 0; max-height: 0; }
.expand-enter-to, .expand-leave-from { opacity: 1; max-height: 500px; }

.target-main {
  flex: 1;
  min-width: 0;
}
</style>
