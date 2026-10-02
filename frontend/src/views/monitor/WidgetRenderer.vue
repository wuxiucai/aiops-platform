<template>
  <div class="widget-renderer">
    <StatCard     v-if="w.widgetType==='stat_card'"    :config="w.config"/>
    <LineChart    v-else-if="w.widgetType==='line_chart'"   :config="w.config"/>
    <TopAlert     v-else-if="w.widgetType==='top_alert'"    :config="w.config"/>
    <TopTemplate  v-else-if="w.widgetType==='top_template'" :config="w.config"/>
    <IncidentList v-else-if="w.widgetType==='incident_list'" :config="w.config"/>
    <MetricCompare v-else-if="w.widgetType==='metric_compare'" :config="w.config"/>
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

defineProps({ w: { type: Object, required: true } })
</script>

<style scoped>
.widget-renderer { display: flex; flex-direction: column; height: 100%; overflow: auto }
</style>
