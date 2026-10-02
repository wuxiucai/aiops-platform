<template>
  <div>
    <el-card shadow="hover">
      <template #header>
        <div class="header-bar">
          <span>监控对象</span>
          <div style="display:flex; gap:8px; align-items:center">
            <el-radio-group v-model="currentTargetId" @change="onTargetChange">
              <el-radio-button v-for="t in targets" :key="t.id" :value="t.id">
                {{ t.name }}
              </el-radio-button>
            </el-radio-group>
            <el-button type="primary" size="small" @click="openCreateDialog" v-if="hasPerm('monitor:target:add')" icon="Plus">新建监控对象</el-button>
          </div>
        </div>
      </template>

      <!-- 当前对象基本信息 -->
      <el-descriptions :column="4" border v-if="currentTarget">
        <el-descriptions-item label="名称">{{ currentTarget.name }}</el-descriptions-item>
        <el-descriptions-item label="类型">
          <el-tag :type="currentTarget.targetType === 'host' ? 'primary' : 'success'">
            {{ currentTarget.targetType }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="地址">{{ currentTarget.ip }}{{ currentTarget.port ? ':' + currentTarget.port : '' }}</el-descriptions-item>
        <el-descriptions-item label="日志服务名">{{ currentTarget.logServiceName || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <!-- 趋势图：host 显示 cpu/mem；service 显示 jvm.heap.usage / app.rt.avg -->
    <el-card shadow="hover" style="margin-top: 16px" v-if="currentTarget">
      <template #header>{{ currentTarget.name }} · 近 1 小时趋势</template>
      <BaseChart :option="trendOption" height="360px" v-loading="chartLoading" />
    </el-card>
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
import { ref, computed, onMounted, onBeforeUnmount, reactive } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import BaseChart from '../../components/BaseChart.vue'
import { listTarget, queryMetric } from '../../api/monitor'
import request from '../../utils/request'
import { useUserStore } from '../../store/user'
import { minutesAgo, nowString } from '../../utils/time'

const userStore = useUserStore()
const hasPerm = (code) => userStore.hasPerm(code)

const targets = ref([])
const currentTargetId = ref(1)
const trendOption = ref({})
const chartLoading = ref(false)
let timer = null

/* 新建 dialog 表单与状态 */
const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive({
  name: '', targetType: 'host', ip: '', os: '', logServiceName: '', description: ''
})

function openCreateDialog () {
  Object.assign(createForm, { name: '', targetType: 'host', ip: '', os: '', logServiceName: '', description: '' })
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
      status: 1
    }
    const r = await request.post('/api/monitor/target', body)
    if (r.code === 200) {
      ElMessage.success('创建成功')
      createVisible.value = false
      const prevFirst = targets.value[0]?.id
      await loadTargets()
      // 切换到刚创建的（可能是首已不是/也很近）
      if (targets.value.length > 0) {
        const nextId = targets.value.find(t => t.name === createForm.name)?.id ?? prevFirst
        currentTargetId.value = nextId
        onTargetChange()
      }
    } else {
      ElMessage.error(r.msg || '创建失败')
    }
  } finally {
    creating.value = false
  }
}

const currentTarget = computed(() => targets.value.find(t => t.id === currentTargetId.value))

/** 每个目标展示的指标 key（按任务书 v2 §2.5 / M2 审查点选） */
function metricKeysFor(t) {
  if (t?.targetType === 'host') return ['cpu.usage', 'mem.usage']
  return ['jvm.heap.usage', 'app.rt.avg']
}

async function loadTargets() {
  targets.value = await listTarget()
  if (targets.value.length) {
    currentTargetId.value = targets.value[0].id
    loadTrend()
  }
}

async function loadTrend() {
  const t = currentTarget.value
  if (!t) return
  chartLoading.value = true
  try {
    const data = await queryMetric({
      targetIds: [t.id],
      metricKeys: metricKeysFor(t),
      startTime: minutesAgo(60),
      endTime: nowString(),
      aggregation: 'avg',
      step: '1m'
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
  loadTargets()
  timer = setInterval(loadTrend, 30_000)
})

onBeforeUnmount(() => clearInterval(timer))
</script>

<style scoped>
.header-bar { display: flex; justify-content: space-between; align-items: center }
</style>
