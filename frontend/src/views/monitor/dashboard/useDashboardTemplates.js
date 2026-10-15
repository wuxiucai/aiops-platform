import { ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listTemplates, getTemplate, createTemplate as apiCreateTemplate, saveTemplate,
  saveDefault, deleteTemplate, moveTemplate
} from '../../../api/dashboard'
import { pageTarget } from '../../../api/monitor'
import { toLayoutItems, toLayoutConfig, normalizeWidgets } from './layout'
import { PRESETS, presetLayoutConfig } from './presets'

/**
 * 大盘模板：列表、打开（模板 → 栅格布局）、新建/一键模板/改名/删除/设默认/移动分组、保存布局、增删 widget。
 * @param currentGroupId 当前分组（决定 load() 优先打开哪个模板）
 * @param loadGroups     移动分组后刷新分组计数
 */
export function useDashboardTemplates ({ currentGroupId, loadGroups }) {
  const allTemplates = ref([])
  const current = ref(null)
  const layout = ref([])
  const loading = ref(false)
  const saving = ref(false)
  const creatingPreset = ref(false)
  const moveTargetGroup = ref(null)

  const filteredTemplates = computed(() => {
    if (currentGroupId.value === null) return allTemplates.value
    if (currentGroupId.value === 0)    return allTemplates.value.filter(t => !t.groupId)
    return allTemplates.value.filter(t => t.groupId === currentGroupId.value)
  })

  /** 栅格项 i 是 String(widgetId)，widget.id 是数字 */
  function getWidget (i) {
    return (current.value?.widgets || []).find(w => String(w.id) === String(i))
  }

  async function loadTemplates () {
    allTemplates.value = (await listTemplates()) || []
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
      /* 优先打开当前分组内的默认/第一个，否则全局默认 */
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
    if (data) data.widgets = normalizeWidgets(data)
    current.value = data
    moveTargetGroup.value = data?.groupId ?? 0
    layout.value = toLayoutItems(data)
  }

  async function createTemplate () {
    const { value } = await ElMessageBox.prompt('请输入新模板名称', '新建模板', {
      confirmButtonText: '创建',
      cancelButtonText: '取消',
      inputPlaceholder: '如：运维值班看板 / 主机监控大盘'
    }).catch(() => ({ value: null }))
    if (!value) return
    try {
      const r = await apiCreateTemplate({ name: value, layoutConfig: [], widgets: [] })
      ElMessage.success('模板已创建，请添加 widget')
      // 先打开新模板：load() 自己会优先打开默认模板
      if (r?.id) await openTemplate(r.id)
      await load()
    } catch (e) { /* 拦截器已弹错 */ }
  }

  async function save () {
    if (!current.value?.id) return
    // 后端删除不在 widgets 里的 widget；此前整份 widgets 原样回传，✕ 掉的 widget 仍留在库里，
    // 重开模板时又被当作"无位置"补回布局
    const kept = new Set(layout.value.map(it => it.i))
    saving.value = true
    try {
      await saveTemplate(current.value.id, {
        name: current.value.name,
        layoutConfig: toLayoutConfig(layout.value),
        widgets: (current.value.widgets || []).filter(w => kept.has(String(w.id)))
      })
      ElMessage.success('布局已保存')
      await load()
    } catch (e) { /* 拦截器已弹错 */ } finally {
      saving.value = false
    }
  }

  async function createFromPreset (key) {
    const preset = PRESETS[key]
    if (!preset || creatingPreset.value) return
    creatingPreset.value = true
    try {
      // 容量观察需要一台主机目标
      const targets = (await pageTarget({ current: 1, size: 100 }))?.records || []
      const host = targets.find(t => t.targetType === 'host')
      if (!host) { ElMessage.warning('请先在监控对象中创建主机目标'); return }

      // 1. 建空模板 → 2. 写入 widgets → 3. 回取真实 widgetId → 4. 写入 layout
      const created = await apiCreateTemplate({ name: preset.name })
      const id = created?.id
      if (!id) { ElMessage.error('模板创建失败'); return }
      const defs = preset.widgets(host.id)
      await saveTemplate(id, { name: preset.name, widgets: defs })
      const refreshed = await getTemplate(id)
      await saveTemplate(id, { layoutConfig: presetLayoutConfig(preset, defs, normalizeWidgets(refreshed)) })

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
      await saveDefault(current.value.id)
      ElMessage.success('已设为默认')
      await load()
    } catch (e) { /* 拦截器已弹错 */ }
  }

  async function renameTemplate (t) {
    const { value } = await ElMessageBox.prompt('新的模板名称', '重命名模板', {
      inputValue: t.name,
      confirmButtonText: '重命名',
      cancelButtonText: '取消',
      inputPattern: /\S/,
      inputErrorMessage: '名称不能为空'
    }).catch(() => ({ value: null }))
    const name = (value || '').trim()
    if (!name || name === t.name) return
    try {
      // 保存接口 body 只带 name 即只更新名称
      await saveTemplate(t.id, { name })
      ElMessage.success('已重命名')
      if (current.value?.id === t.id) current.value.name = name
      await loadTemplates()
    } catch (e) { /* 拦截器已弹错 */ }
  }

  async function removeTemplate (t) {
    try {
      await ElMessageBox.confirm(`确认删除模板「${t.name}」？其下所有 widget 将一并删除。`, '删除模板', {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning'
      })
    } catch (e) { return }
    try {
      await deleteTemplate(t.id)
      ElMessage.success('已删除')
      // 删掉的若是当前模板，由 load() 重新打开默认/第一个
      if (current.value?.id === t.id) {
        current.value = null
        layout.value = []
      }
      await load()
    } catch (e) { /* 拦截器已弹错 */ }
  }

  async function moveCurrent (gid) {
    if (!current.value?.id) return
    const groupId = gid === 0 ? null : gid
    try {
      await moveTemplate(current.value.id, groupId)
      ElMessage.success('已移动')
      current.value.groupId = groupId
      await loadTemplates()
      await loadGroups()
    } catch (e) { /* 拦截器已弹错 */ }
  }

  /** 只从布局移除；点「保存布局」时随 widgets 一并删除 */
  function removeWidget (i) {
    const idx = layout.value.findIndex(it => it.i === i)
    if (idx >= 0) layout.value.splice(idx, 1)
  }

  /**
   * 向当前模板追加一个 widget。新 widget 的 id 由后端生成，所以分两步：
   * 先写入 widgets（连同未保存的布局改动），回取后按"之前不存在的 id"找到它，排到末尾默认位置，再写一次 layout。
   * 不能按 sort === 原数量 找：删过 widget 后 sort 不连续，会撞上旧 widget 的 sort，把旧的当成新的。
   * 只有第一步失败才抛出（调用方保持对话框打开）；之后的失败不再抛，否则重试会重复添加。
   */
  async function appendWidget ({ widgetType, title, config }) {
    const id = current.value?.id
    if (!id) {
      ElMessage.warning('请先选中一个模板再添加 Widget')
      throw new Error('no template')
    }
    const existing = current.value.widgets || []
    const known = new Set(existing.map(w => String(w.id)))
    const sort = existing.reduce((m, w) => Math.max(m, w.sort ?? 0), -1) + 1
    await saveTemplate(id, {
      widgets: [...existing, { widgetType, title, config: JSON.stringify(config), sort }],
      layoutConfig: toLayoutConfig(layout.value)
    })
    try {
      const realWidgets = normalizeWidgets(await getTemplate(id))
      const added = realWidgets.find(w => !known.has(String(w.id)))
      if (added) {
        // 放在现有布局最下方；按序号取默认位置会与被拖动/缩放过的 widget 重叠
        const bottom = layout.value.reduce((m, it) => Math.max(m, it.y + it.h), 0)
        layout.value = [...layout.value, { i: String(added.id), x: 0, y: bottom, w: 6, h: 4 }]
        await saveTemplate(id, { widgets: realWidgets, layoutConfig: toLayoutConfig(layout.value) })
      }
      await openTemplate(id)
    } catch (e) { console.warn('layout save failed:', e) }
  }

  return {
    allTemplates, filteredTemplates, current, layout, loading, saving, creatingPreset, moveTargetGroup,
    getWidget, load, loadTemplates, openTemplate, createTemplate, save, createFromPreset,
    setDefault, renameTemplate, removeTemplate, moveCurrent, removeWidget, appendWidget
  }
}
