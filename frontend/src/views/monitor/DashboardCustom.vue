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
              <span class="tpl-name" :title="t.name + '（双击可重命名）'">{{ t.name }}</span>
              <el-tag v-if="t.isDefault === 1" size="small" type="success">默认</el-tag>
              <span class="ops">
                <el-button size="small" link type="primary" @click="renameTemplate(t)">改名</el-button>
                <el-button size="small" link type="danger" @click="removeTemplate(t)">删</el-button>
              </span>
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
            <el-button v-if="mode==='edit'" size="small" @click="openAddWidget">添加 Widget</el-button>
            <el-button size="small" type="success" @click="setDefault" :disabled="!current || current.isDefault === 1">设为默认</el-button>
          </div>
        </div>

        <el-card shadow="never" v-loading="loading">
          <!-- 绝对定位 + 手动鼠标拖拽（稳定可靠） -->
          <div v-if="layout.length > 0" ref="gridEl" class="dash-grid-abs" :style="{ height: gridHeight + 'px' }">
            <div
              v-for="item in layout"
              :key="item.i"
              class="dash-cell-abs"
              :style="cellStyle(item)"
            >
              <div class="widget-box">
                <div class="widget-head"
                     @mousedown.prevent="mode==='edit' && startMove(item, $event)">
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
                     @mousedown.prevent="startResize(item, $event)"></div>
              </div>
            </div>
          </div>
          <el-empty v-if="layout.length === 0 && !loading" description="无 widget，点击【添加 Widget】新建" />
        </el-card>
      </el-col>
    </el-row>

    <AddWidgetDialog v-model="addVisible" :submit="appendWidget"/>

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
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import WidgetRenderer from './WidgetRenderer.vue'
import AddWidgetDialog from './dashboard/AddWidgetDialog.vue'
import { useDashboardGroups } from './dashboard/useDashboardGroups'
import { useDashboardTemplates } from './dashboard/useDashboardTemplates'
import { GRID_COLS, ROW_HEIGHT, cellStyle, gridHeight as calcGridHeight } from './dashboard/layout'
import { useDragResize } from '../../composables/useDragResize'

const mode = ref('view')
const globalHours = ref(null)  // null=不联动；1/6/24=全局时间范围联动
const addVisible = ref(false)
const gridEl = ref(null)

const {
  ungroupedCount, currentGroupId, flatGroups, pickGroup, loadGroups,
  groupDialogVisible, groupForm, openGroupDialog, saveGroup, onDeleteGroup
} = useDashboardGroups(() => loadTemplates())

const {
  allTemplates, filteredTemplates, current, layout, loading, saving, creatingPreset, moveTargetGroup,
  getWidget, load, loadTemplates, openTemplate, createTemplate, save, createFromPreset,
  setDefault, renameTemplate, removeTemplate, moveCurrent, removeWidget, appendWidget
} = useDashboardTemplates({ currentGroupId, loadGroups })

const { startMove, startResize } = useDragResize(gridEl, { cols: GRID_COLS, rowHeight: ROW_HEIGHT })

const gridHeight = computed(() => calcGridHeight(layout.value))

function openAddWidget () {
  if (!current.value?.id) {
    ElMessage.warning('请先打开或新建一个模板')
    return
  }
  addVisible.value = true
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
.tpl-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; cursor: text; }
/* 模板操作按钮常显（hover 才出现曾被反馈"看不见"）。
   注意：tpl-item.active 是浅蓝底，不能像 group-item（深蓝底）那样把按钮刷成白色 */
.tpl-side-list .tpl-item .ops { display: flex; gap: 0; flex-shrink: 0 }
/* position: relative —— 右下角缩放手柄相对它定位 */
.widget-box { display: flex; flex-direction: column; height: 100%; border: 1px solid #e4e7ed; border-radius: 3px; background: #fff; position: relative }
.widget-head { display: flex; justify-content: space-between; align-items: center; padding: 4px 8px; border-bottom: 1px solid #f0f0f0; font-size: 12px; user-select: none; -webkit-user-select: none; cursor: grab; }
.widget-head:active { cursor: grabbing; }
.widget-title { color: #303133; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; display: flex; align-items: center; gap: 6px; }
.drag-handle { color: #909399; font-size: 14px; letter-spacing: -2px; }
.widget-body { flex: 1; overflow: hidden; padding: 4px }

/* 绝对定位栅格：子项坐标由 dashboard/layout.js 的 cellStyle 计算 */
.dash-grid-abs { position: relative; width: 100%; min-height: 600px; }
.dash-cell-abs { position: absolute; overflow: hidden; }
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
</style>
