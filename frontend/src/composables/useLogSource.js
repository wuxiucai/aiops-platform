import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listDatasources, listIndexConfigs } from '../api/esa'

const TYPE_LABELS = { elasticsearch: 'Elasticsearch', opensearch: 'OpenSearch', compatible: 'ES 兼容' }

/**
 * 日志来源选择（ES 数据源 + 索引映射），日志检索与自然语言查日志共用。
 * 选择规则与后端 LogSourceResolver 一致：默认数据源优先，切换数据源后取其第一个启用的索引映射。
 */
export function useLogSource () {
  const datasourceOptions = ref([])
  const indexOptions = ref([])
  const datasourceId = ref(null)
  const indexConfigId = ref(null)

  function typeLabel (t) {
    return TYPE_LABELS[t] || t || 'Elasticsearch'
  }

  async function loadIndexConfigs () {
    const list = await listIndexConfigs(datasourceId.value)
    indexOptions.value = (list || []).filter(i => i.enabled === 1 || i.enabled == null)
    indexConfigId.value = indexOptions.value.length ? indexOptions.value[0].id : null
  }

  async function loadDatasources () {
    const list = await listDatasources()
    datasourceOptions.value = (list || []).filter(d => d.status === 1)
    if (!datasourceOptions.value.length) {
      ElMessage.warning('暂无可用 ES 数据源，请先在数据源管理中配置')
      return
    }
    const def = datasourceOptions.value.find(d => d.isDefault === 1) || datasourceOptions.value[0]
    datasourceId.value = def.id
    await loadIndexConfigs()
  }

  return { datasourceOptions, indexOptions, datasourceId, indexConfigId, typeLabel, loadDatasources, loadIndexConfigs }
}
