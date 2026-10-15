import { ref, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getGroupTree, createGroup, updateGroup, deleteGroup } from '../../../api/dashboard'

/**
 * 大盘分组：分组树加载、当前分组（与 URL ?group= 双向同步）、新建/重命名/删除。
 * @param onChanged 分组增删改后的回调（模板列表里的 groupId / 计数需要随之刷新）
 */
export function useDashboardGroups (onChanged) {
  const route = useRoute()
  const router = useRouter()

  const groupNodes = ref([])
  const ungroupedCount = ref(0)
  const currentGroupId = ref(null)  // null=全部， 0=未归类， >0 = 某分组

  const groupDialogVisible = ref(false)
  const groupForm = ref({ id: null, name: '', parentId: 0 })

  /* 把平铺 node 列表转有序树形 （深度优先 + _depth)，便于平铺渲染缩进 */
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

  function pickGroup (gid) {
    currentGroupId.value = gid
    const q = { ...route.query }
    if (gid === null) delete q.group
    else q.group = String(gid)
    router.replace({ query: q })
  }

  watch(() => route.query.group, (g) => {
    if (g === undefined) currentGroupId.value = null
    else currentGroupId.value = Number(g)
  }, { immediate: true })

  async function loadGroups () {
    try {
      const r = await getGroupTree()
      groupNodes.value = r?.nodes || []
      ungroupedCount.value = r?.ungroupedCount || 0
    } catch (e) { /* 静默 */ }
  }

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
      if (groupForm.value.id) await updateGroup(groupForm.value)
      else                    await createGroup(groupForm.value)
      ElMessage.success('已保存')
      groupDialogVisible.value = false
      await loadGroups()
      await onChanged()
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
      await deleteGroup(g.id)
      ElMessage.success('已删除')
      if (currentGroupId.value === g.id) currentGroupId.value = null
      await loadGroups()
      await onChanged()
    } catch (e) { /* 拦截器已弹错 */ }
  }

  return {
    ungroupedCount, currentGroupId, flatGroups, pickGroup, loadGroups,
    groupDialogVisible, groupForm, openGroupDialog, saveGroup, onDeleteGroup
  }
}
