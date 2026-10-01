<template>
  <div class="chat-page">
    <div class="sidebar">
      <div class="side-head">
        <el-button type="primary" size="small" block @click="newChat">
          <el-icon><Plus /></el-icon> 新对话
        </el-button>
      </div>
      <el-scrollbar class="side-list">
        <div
          v-for="(s, i) in sessions" :key="i"
          class="side-item"
          :class="{ active: i === currentSession }"
          @click="currentSession = i"
        >
          <div class="title">{{ s.title || '未命名对话' }}</div>
          <div class="meta">{{ s.messages.length }} 条 · {{ s.updatedAt }}</div>
        </div>
        <el-empty v-if="sessions.length === 0" description="暂无对话" :image-size="80"/>
      </el-scrollbar>
    </div>

    <div class="main">
      <div class="messages" ref="msgRef" v-loading="thinking">
        <div v-for="(m, i) in current.messages" :key="i" :class="['msg', m.role]">
          <div class="avatar">{{ m.role === 'user' ? '我' : 'AI' }}</div>
          <div class="bubble">
            <div class="content" v-if="m.role === 'user'">{{ m.content }}</div>
            <div class="content md" v-else-if="m.content" v-html="mdRender(m.content)"></div>
            <div class="content thinking" v-else>思考中...</div>
            <div v-if="m.fallback" class="fallback-tag">⚠ 系统降级（isLlmFallback）</div>
            <div v-if="m.done === false" class="stream-cursor">▌</div>
          </div>
        </div>
        <div v-if="current.messages.length === 0" class="empty">
          <p>试一下：</p>
          <el-tag v-for="q in examples" :key="q" style="margin: 4px" @click="ask(q)">{{ q }}</el-tag>
        </div>
      </div>

      <div class="input-row">
        <el-input
          v-model="input"
          type="textarea"
          :rows="2"
          placeholder="问点什么（支持中文）..."
          @keydown.ctrl.enter="send"
        />
        <el-button type="primary" @click="send" :loading="thinking" style="margin-left: 8px">
          发送 (Ctrl+Enter)
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

const input = ref('')
const thinking = ref(false)
const msgRef = ref(null)
const sessions = ref([{ title: '默认对话', messages: [], updatedAt: new Date().toISOString().substring(0, 16) }])
const currentSession = ref(0)
const current = computed(() => sessions.value[currentSession.value])
const examples = [
  '上午 cpu 最高的服务是哪个',
  '现在有多少 unresolved 告警',
  '近 1 小时 order-service 的错误日志有什么特点'
]

function newChat () {
  sessions.value.push({ title: '对话 ' + (sessions.value.length + 1), messages: [], updatedAt: new Date().toISOString().substring(0, 16) })
  currentSession.value = sessions.value.length - 1
}

function scrollBottom () {
  nextTick(() => {
    if (msgRef.value) msgRef.value.scrollTop = msgRef.value.scrollHeight
  })
}

function mdRender (text) {
  if (!text) return ''
  return text.replace(/</g, '&lt;').replace(/\n/g, '<br/>')
}

function ask (q) {
  input.value = q
  send()
}

async function send () {
  const q = input.value.trim()
  if (!q || thinking.value) return
  thinking.value = true
  current.value.messages.push({ role: 'user', content: q })
  const aiMsg = ref({ role: 'assistant', content: '', done: false, fallback: false })
  current.value.messages.push(aiMsg.value)
  input.value = ''
  scrollBottom()

  try {
    const token = localStorage.getItem('aiops-token')
    const url = '/api/ai/chat/stream?question=' + encodeURIComponent(q)
    const resp = await fetch(url, {
      method: 'GET',
      headers: { 'Authorization': 'Bearer ' + token }
    })
    if (!resp.ok) {
      aiMsg.value.content = `调用失败：HTTP ${resp.status}`
      aiMsg.value.done = true
      thinking.value = false
      return
    }
    if (!resp.body) {
      aiMsg.value.content = '响应无流（浏览器不支持 ReadableStream）'
      aiMsg.value.done = true
      thinking.value = false
      return
    }

    const reader = resp.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buf = ''
    let fallback = false

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })

      // SSE 按 \n\n 分段
      let idx
      while ((idx = buf.indexOf('\n\n')) >= 0) {
        const frame = buf.slice(0, idx).trim()
        buf = buf.slice(idx + 2)
        if (!frame.startsWith('data:')) continue
        const dataStr = frame.replace(/^data:\s*/, '')
        try {
          const evt = JSON.parse(dataStr)
          if (evt.type === 'token') {
            aiMsg.value.content += evt.content || evt.data || ''
            scrollBottom()
          } else if (evt.type === 'done') {
            aiMsg.value.done = true
          } else if (evt.type === 'error') {
            aiMsg.value.content += '\n\n⚠ ' + (evt.message || evt.data?.message || 'error')
            aiMsg.value.done = true
          } else if (evt.type === 'fallback') {
            fallback = true
            aiMsg.value.fallback = true
          }
          if (evt.isLlmFallback === true) {
            aiMsg.value.fallback = true
          }
        } catch (e) {
          // 未完整 JSON：先压入 buf 等待下一段（因为是按 \n\n 切，面积上两次仍然是 JSON 完整）
          buf = dataStr + buf
          break
        }
      }
    }
    if (!aiMsg.value.done) aiMsg.value.done = true
    current.value.updatedAt = new Date().toISOString().substring(0, 16)
  } catch (e) {
    aiMsg.value.content += '\n\n⚠ 连接错误: ' + (e?.message || '未知')
    aiMsg.value.done = true
  } finally {
    thinking.value = false
  }
}
</script>

<style scoped>
.chat-page { display: flex; height: calc(100vh - 60px); }
.sidebar { width: 260px; border-right: 1px solid #e8e8e8; display: flex; flex-direction: column; }
.side-head { padding: 12px; border-bottom: 1px solid #e8e8e8; }
.side-list { flex: 1; overflow: hidden; }
.side-item { padding: 12px; cursor: pointer; border-bottom: 1px solid #f5f5f5; }
.side-item.active { background: #ecf5ff; border-left: 3px solid #409eff; }
.side-item .title { font-weight: 500; font-size: 14px; margin-bottom: 4px; }
.side-item .meta { font-size: 12px; color: #999; }

.main { flex: 1; display: flex; flex-direction: column; }
.messages { flex: 1; overflow-y: auto; padding: 20px; background: #fafafa; }
.msg { display: flex; margin-bottom: 16px; }
.msg.user { justify-content: flex-end; }
.avatar { width: 36px; height: 36px; border-radius: 50%; background: #409eff; color: #fff; display: flex; align-items: center; justify-content: center; font-size: 12px; margin: 0 12px; flex-shrink: 0; }
.msg.user .avatar { background: #67c23a; order: 1; }
.msg.assistant .avatar { background: #909399; }
.bubble { max-width: 70%; background: #fff; padding: 10px 14px; border-radius: 8px; box-shadow: 0 1px 2px rgba(0,0,0,0.05); white-space: pre-wrap; word-wrap: break-word; }
.msg.user .bubble { background: #e1f3d8; }
.content { font-size: 14px; line-height: 1.6; white-space: pre-wrap; }
.content.md:deep(p) { margin: 4px 0; }
.content.md:deep(pre) { background: #f5f5f5; padding: 8px; border-radius: 4px; overflow-x: auto; }
.content.md:deep(code) { background: #f5f5f5; padding: 2px 4px; border-radius: 2px; }
.fallback-tag { color: #e6a23c; font-size: 12px; margin-top: 6px; padding: 2px 6px; background: #fdf6ec; border-radius: 2px; }
.stream-cursor { animation: blink 1s infinite; }
.empty { text-align: center; color: #999; margin-top: 80px; }
.empty p { cursor: default; }
.input-row { padding: 12px 20px; border-top: 1px solid #e8e8e8; display: flex; background: #fff; }
@keyframes blink { 0%,50% { opacity: 1 } 51%,100% { opacity: 0 } }
</style>
