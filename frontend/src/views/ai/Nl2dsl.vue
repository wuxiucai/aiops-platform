<template>
  <div class="nl2dsl-page">
    <el-row :gutter="12">
      <!-- 左侧：输入 + 历史 -->
      <el-col :span="9">
        <el-card shadow="hover" style="margin-bottom: 12px">
          <template #header>
            <div class="header-bar">
              <span>自然语言 → ES DSL（NL2DSL 工作台）</span>
              <el-button link type="primary" size="small" @click="historyVisible = true; onLoadHistory()">历史查询</el-button>
            </div>
          </template>

          <div class="quick-chips">
            <span class="chip-label">快捷问题：</span>
            <el-tag
              v-for="q in quickQuestions"
              :key="q"
              class="chip"
              size="small"
              @click="onQuick(q)"
            >{{ q }}</el-tag>
          </div>

          <el-input
            v-model="question"
            type="textarea"
            :rows="5"
            maxlength="200"
            show-word-limit
            placeholder="例如：过去 1 小时 payment-service 的 ERROR 日志 / OR"
            style="margin-top: 8px"
          />

          <div class="actions">
            <el-button
              type="primary"
              :loading="generating"
              @click="onGenerate"
            >生成 DSL</el-button>
            <el-button
              type="success"
              :disabled="!canExecute"
              :loading="executing"
              @click="onExecute"
            >执行查询</el-button>
            <el-button @click="onClear">清空</el-button>
          </div>

          <el-alert
            v-if="genError"
            :title="genError"
            type="error"
            show-icon
            :closable="false"
            style="margin-top: 12px"
          />
        </el-card>

        <el-card shadow="hover">
          <template #header>
            <span>生成信息</span>
          </template>
          <div v-if="genResult" class="gen-info">
            <el-alert
              :title="genResult.validated ? 'DSL 校验通过 (validated=true)' : 'DSL 校验未通过 (validated=false)'"
              :type="genResult.validated ? 'success' : 'error'"
              show-icon
              :closable="false"
            />
            <div v-if="!genResult.validated && (genResult.errors && genResult.errors.length)" class="err-list">
              <div class="err-title">错误列表：</div>
              <ul>
                <li v-for="(e, i) in genResult.errors" :key="i">{{ e }}</li>
              </ul>
            </div>
            <div class="meta-row">
              <span>重试次数：<b>{{ genResult.retryCount ?? 0 }}</b></span>
              <span>生成耗时：<b>{{ genResult.latencyMs ?? '-' }} ms</b></span>
              <span>recordId：<b>{{ genResult.recordId ?? '-' }}</b></span>
            </div>
            <div class="dsl-title">生成的 DSL（JSON）：</div>
            <pre class="dsl-box">{{ prettyJson(genResult.dsl) }}</pre>
          </div>
          <el-empty v-else description="尚未生成，输入上方问题并点击“生成 DSL”" :image-size="60" />
        </el-card>
      </el-col>

      <!-- 右侧：执行结果 -->
      <el-col :span="15">
        <el-card shadow="hover">
          <template #header>
            <span>执行结果</span>
          </template>

          <div v-if="execResult" class="exec-result">
            <div class="top-row">
              <div class="stat">
                <div class="stat-num">{{ execResult.total ?? 0 }}</div>
                <div class="stat-label">命中总数</div>
              </div>
              <div class="stat">
                <div class="stat-num">{{ execResult.latencyMs ?? '-' }} ms</div>
                <div class="stat-label">执行耗时</div>
              </div>
              <div class="stat">
                <div class="stat-num">{{ (execResult.records || []).length }}</div>
                <div class="stat-label">返回条数</div>
              </div>
            </div>

            <el-alert
              v-if="execResult.answer"
              :title="execResult.answer"
              type="info"
              show-icon
              :closable="false"
              style="margin: 10px 0"
            />

            <div class="chart-wrap" v-if="chartOption">
              <div class="chart-title">命中按级别聚合（aggs.by_level）</div>
              <BaseChart :option="chartOption" height="220px" />
            </div>

            <el-divider content-position="left">Top 命中明细</el-divider>
            <el-table :data="execResult.records || []" border stripe max-height="420" size="small">
              <el-table-column prop="time" label="时间" width="160" />
              <el-table-column prop="service" label="服务" width="140" show-overflow-tooltip />
              <el-table-column label="级别" width="80">
                <template #default="{ row }">
                  <el-tag :type="levelType(row.level)" size="small">{{ row.level }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="消息" min-width="280">
                <template #default="{ row }">
                  <span :title="row.message">{{ truncate(row.message, 120) }}</span>
                </template>
              </el-table-column>
            </el-table>
          </div>
          <el-empty v-else description="生成 DSL 之后，点“执行查询”会在右侧渲染结果" :image-size="80" />
        </el-card>
      </el-col>
    </el-row>

    <!-- 历史 dialog -->
    <el-dialog v-model="historyVisible" title="NL 查询历史（最近 20 条）" width="1100px" destroy-on-close>
      <el-table :data="historyRows" border stripe v-loading="historyLoading" max-height="520" size="small">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="问题" min-width="220">
          <template #default="{ row }">
            <span :title="row.question">{{ truncate(row.question, 60) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="DSL" min-width="240">
          <template #default="{ row }">
            <span class="mono" :title="row.generatedDsl">{{ truncate(row.generatedDsl, 80) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="校验" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.validated === 1 ? 'success' : 'danger'" size="small">
              {{ row.validated === 1 ? '通过' : '拒绝' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="执行" width="70" align="center">
          <template #default="{ row }">
            <el-tag :type="row.executed === 1 ? 'success' : 'info'" size="small">
              {{ row.executed === 1 ? '是' : '否' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="hitCount" label="命中" width="80" align="right" />
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="onReask(row)">重新提问</el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="historyVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { nl2dslGenerate, nl2dslExecute, nlQueryHistory } from '../../api/llm'
import BaseChart from '../../components/BaseChart.vue'

// ============ 输入 / 生成 ============
const question = ref('')
const generating = ref(false)
const genError = ref('')
const genResult = ref(null) // {recordId, dsl, validated, errors, retryCount, latencyMs}

const quickQuestions = [
  '过去 1 小时的 ERROR 日志',
  '过去 24 小时 payment-service 的超时错误',
  '最近 5 分钟内 WARN 级别以上的日志',
  '过去 7 天 包含 timeout 的 ERROR 日志按服务聚合'
]

function onQuick(q) {
  question.value = q
}

async function onGenerate() {
  const q = (question.value || '').trim()
  if (!q) {
    ElMessage.warning('请先输入自然语言问题')
    return
  }
  generating.value = true
  genError.value = ''
  genResult.value = null
  execResult.value = null
  try {
    const data = await nl2dslGenerate(q)
    // 后端期望返回 { recordId, dsl(JSON), validated, errors[], retryCount, latencyMs }
    genResult.value = {
      recordId: data?.recordId ?? null,
      dsl: data?.dsl ?? data?.generatedDsl ?? null,
      validated: !!data?.validated,
      errors: Array.isArray(data?.errors) ? data.errors : (data?.errorMsg ? [data.errorMsg] : []),
      retryCount: data?.retryCount ?? 0,
      latencyMs: data?.latencyMs ?? null
    }
    if (genResult.value.validated) {
      ElMessage.success('DSL 生成并校验通过')
    } else {
      ElMessage.warning('DSL 已生成但未通过校验，可查看错误列表')
    }
  } catch (e) {
    genError.value = e?.message || '生成失败'
  } finally {
    generating.value = false
  }
}

function onClear() {
  question.value = ''
  genResult.value = null
  execResult.value = null
  genError.value = ''
}

const canExecute = computed(() => !!genResult.value && !!genResult.value.recordId && !!genResult.value.validated)

// ============ 执行 ============
const executing = ref(false)
const execResult = ref(null) // {total, latencyMs, answer, records[], aggs:{by_level:[{key,count}]}}

async function onExecute() {
  if (!canExecute.value) return
  executing.value = true
  try {
    const data = await nl2dslExecute(genResult.value.recordId)
    execResult.value = {
      total: data?.total ?? data?.hitCount ?? 0,
      latencyMs: data?.latencyMs ?? null,
      answer: data?.answer ?? '',
      records: data?.records || [],
      aggs: data?.aggs || {}
    }
    ElMessage.success(`命中 ${execResult.value.total} 条`)
  } catch (e) {
    ElMessage.error(e?.message || '执行失败')
  } finally {
    executing.value = false
  }
}

const chartOption = computed(() => {
  const aggs = execResult.value?.aggs
  if (!aggs) return null
  const buckets = aggs.by_level || aggs.byLevel || []
  if (!Array.isArray(buckets) || buckets.length === 0) return null
  const keys = buckets.map(b => b.key ?? b.level ?? '-')
  const vals = buckets.map(b => b.count ?? b.docCount ?? b.doc_count ?? 0)
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 20, top: 16, bottom: 28 },
    xAxis: { type: 'category', data: keys },
    yAxis: { type: 'value' },
    series: [{
      name: '命中量',
      type: 'bar',
      data: vals,
      itemStyle: { color: '#4361ee', borderRadius: [4, 4, 0, 0] }
    }]
  }
})

// ============ 历史 ============
const historyVisible = ref(false)
const historyLoading = ref(false)
const historyRows = ref([])

async function onLoadHistory() {
  historyLoading.value = true
  try {
    const data = await nlQueryHistory(20)
    historyRows.value = Array.isArray(data) ? data : (data?.records || [])
  } catch (e) {
    historyRows.value = []
  } finally {
    historyLoading.value = false
  }
}

function onReask(row) {
  question.value = row.question || ''
  historyVisible.value = false
  onGenerate()
}

// ============ 工具 ============
function prettyJson(s) {
  if (s === null || s === undefined) return ''
  let v = s
  if (typeof s === 'object') {
    try { return JSON.stringify(s, null, 2) } catch (e) { return String(s) }
  }
  v = String(s).trim()
  if (!v) return ''
  try {
    return JSON.stringify(JSON.parse(v), null, 2)
  } catch (e) {
    return v
  }
}

function truncate(s, n) {
  if (s === null || s === undefined) return ''
  const v = String(s)
  return v.length > n ? v.slice(0, n) + '…' : v
}

function levelType(l) {
  if (l === 'ERROR' || l === 'FATAL') return 'danger'
  if (l === 'WARN') return 'warning'
  if (l === 'DEBUG' || l === 'TRACE') return 'info'
  return 'success'
}

function formatTime(t) {
  if (!t) return '-'
  if (typeof t === 'string') return t.replace('T', ' ').slice(0, 19)
  if (t instanceof Date) {
    const pad = n => String(n).padStart(2, '0')
    return `${t.getFullYear()}-${pad(t.getMonth() + 1)}-${pad(t.getDate())} ${pad(t.getHours())}:${pad(t.getMinutes())}:${pad(t.getSeconds())}`
  }
  return String(t)
}
</script>

<style scoped>
.nl2dsl-page { padding: 0 }
.header-bar { display: flex; justify-content: space-between; align-items: center }
.quick-chips { display: flex; flex-wrap: wrap; align-items: center; gap: 6px }
.chip-label { font-size: 12px; color: #909399 }
.chip { cursor: pointer; }
.actions { display: flex; gap: 8px; margin-top: 10px }
.gen-info { font-size: 13px }
.err-list { margin-top: 8px; color: #f56c6c; font-size: 12px }
.err-list ul { margin: 4px 0 0 18px; padding: 0 }
.err-title { font-weight: 600 }
.meta-row {
  display: flex; gap: 18px; flex-wrap: wrap;
  margin: 10px 0; color: #606266; font-size: 12px;
}
.dsl-title { font-size: 12px; color: #909399; margin: 8px 0 4px }
.dsl-box {
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
  padding: 10px;
  border-radius: 4px;
  font-size: 12px;
  max-height: 320px;
  overflow: auto;
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-family: Consolas, Monaco, monospace;
}
.mono { font-family: Consolas, Monaco, monospace; font-size: 12px }

.exec-result { font-size: 13px }
.top-row {
  display: flex; gap: 24px; flex-wrap: wrap;
  padding: 12px; background: #fafafa; border-radius: 4px;
}
.stat { min-width: 120px; text-align: center }
.stat-num { font-size: 22px; font-weight: 600; color: #303133 }
.stat-label { font-size: 12px; color: #909399; margin-top: 4px }
.chart-wrap { margin-top: 12px }
.chart-title { font-size: 12px; color: #909399; margin-bottom: 4px }
</style>
