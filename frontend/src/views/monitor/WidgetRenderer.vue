<template>
  <div class="widget-renderer">
    <StatCard     v-if="w?.widgetType==='stat_card'"     :config="w.config"/>
    <LineChart    v-else-if="w?.widgetType==='line_chart'"    :config="w.config" :global-hours="globalHours"/>
    <TopAlert     v-else-if="w?.widgetType==='top_alert'"     :config="w.config" :global-hours="globalHours"/>
    <TopTemplate  v-else-if="w?.widgetType==='top_template'" :config="w.config"/>
    <IncidentList v-else-if="w?.widgetType==='incident_list'" :config="w.config"/>
    <MetricCompare v-else-if="w?.widgetType==='metric_compare'" :config="w.config" :global-hours="globalHours"/>
    <el-empty v-else description="未知类型" :image-size="40"/>
  </div>
</template>

<script setup>
import StatCard    from './widgets/StatCard.vue'
import LineChart   from './widgets/LineChart.vue'
import TopAlert    from './widgets/TopAlert.vue'
import TopTemplate from './widgets/TopTemplate.vue'
import IncidentList from './widgets/IncidentList.vue'
import MetricCompare from './widgets/MetricCompare.vue'

/* globalHours：查看模式顶部的「时间范围联动」选择器（null = 不联动，各 widget 用自己的配置） */
const props = defineProps({
  w: { type: Object, required: false, default: null },
  globalHours: { type: Number, required: false, default: null }
})
</script>

<style scoped>
.widget-renderer { display: flex; flex-direction: column; height: 100%; overflow: auto }
</style>
