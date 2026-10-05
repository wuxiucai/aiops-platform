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
          <div class="avatar" :class="m.role === 'user' ? 'avatar-user' : 'avatar-ai'">
            {{ m.role === 'user' ? '我' : 'AI' }}
          </div>
          <div class="bubble">
            <div class="content" v-if="m.role === 'user'">{{ m.content }}</div>
            <div class="content md" v-else-if="m.content" v-html="mdRender(m.content)"></div>
            <div class="content thinking" v-else>思考中...</div>
            <div v-if="m.fallback" class="fallback-tag">⚠ 系统降级（isLlmFallback）</div>
            <div v-if="m.done === false" class="stream-cursor">▌</div>
          </div>
        </div>
        <div v-if="current.messages.length === 0" class="empty">
          <div class="empty-icon">🤖</div>
          <p class="empty-title">我是你的智能运维助手</p>
          <p class="empty-sub">可以问我关于监控指标、告警与日志的问题：</p>
          <div class="example-tags">
            <el-tag v-for="q in examples" :key="q" class="example-tag" @click="ask(q)">{{ q }}</el-tag>
          </div>
        </div>
      </div>

      <div class="input-row">
        <el-input
          v-model="input"
          type="textarea"
          :rows="2"
          resize="none"
          placeholder="问点什么（支持中文），Ctrl + Enter 发送..."
          @keydown.ctrl.enter="send"
        />
        <el-button type="primary" round class="send-btn" @click="send" :loading="thinking">
          <el-icon style="margin-right: 4px"><Promotion /></el-icon>发送
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import { Plus, Promotion } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getToken, removeToken, removeUser } from '../../utils/auth'

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
    // 用统一的 auth util：token key 是 'aiops_token'（下划线）
    // 之前 hardcode 'aiops-token'（连字符）导致永远拿到 null
    const token = getToken()
    // POST + JSON body：GET query 中文在 Windows 上乱码（Tomcat 不按 UTF-8 解码）
    const resp = await fetch('/api/ai/chat/stream', {
      method: 'POST',
      headers: {
        'Authorization': 'Bearer ' + token,
        'Content-Type': 'application/json;charset=UTF-8',
        'Accept': 'text/event-stream'
      },
      body: JSON.stringify({ question: q })
    })
    if (!resp.ok) {
      aiMsg.value.content = `调用失败：HTTP ${resp.status}`
      aiMsg.value.done = true
      thinking.value = false
      return
    }
    // 后端 JwtAuthFilter.write401 返回 HTTP 200 + application/json body {code:401,...}
    // Content-Type 就不是 text/event-stream，需要在读流之前识别
    const contentType = (resp.headers.get('content-type') || '').toLowerCase()
    if (!contentType.includes('text/event-stream')) {
      // 非 SSE，按普通 JSON 读一次性
      let body
      try { body = await resp.json() } catch { body = null }
      if (body && body.code === 401) {
        aiMsg.value.content = '⚠ ' + (body.msg || '登录已过期，请重新登录')
        aiMsg.value.done = true
        thinking.value = false
        // 用统一的 auth util 清理（与 request.js 拦截器行为对齐）
        removeToken()
        removeUser()
        setTimeout(() => { window.location.href = '/login' }, 800)
        return
      }
      aiMsg.value.content = '⚠ ' + (body?.msg || `非预期响应（content-type: ${contentType || 'unknown'}）`)
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
    // 应用层结束标志：后端发来 {type:done} 或 {type:error} 时置 true。
    // 结束循环不能依赖 reader.read() 的流结束信号（done=true）：
    // 经过 vite dev proxy 时，Spring 端 emitter.complete() 关闭 TCP
    // 后 proxy 不一定立刻把 close 传播给浏览器，read() 可能长时间挂住，
    // finally 不执行 → thinking 不复位 → 页面 loading 一直转。
    let appDone = false

    // 兜底超时：即使 done 帧丢了/连接异常挂住，也让 UI 在 N 秒后必然恢复，
    // 否则用户只能刷新页面。120s 与后端 SseEmitter 超时对齐。
    const overallTimeout = setTimeout(() => {
      if (!appDone) {
        console.warn('[chat] SSE 超时兜底：120s 未收到 done，强制结束')
        appDone = true
        aiMsg.value.content += '\n\n⚠ 响应超时（连接已中断）'
        aiMsg.value.done = true
        reader.cancel().catch(() => {})
      }
    }, 120_000)

    while (!appDone) {
      const { done, value } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })

      // SSE 帧以空行（\n\n）结尾，帧内可能有多行：data:/event:/id:/retry:。
      // 后端 Spring SseEmitter 默认会输出 "data:{...}\nevent:token\n\n"，
      // 因此必须逐行解析拼接 data:，不能假设整帧只有一行 data。
      let idx
      while ((idx = buf.indexOf('\n\n')) >= 0) {
        const frame = buf.slice(0, idx)
        buf = buf.slice(idx + 2)
        // 拼接帧内所有 data: 行（去前缀 + 去行尾 \r）
        const dataStr = frame.split('\n')
          .filter(line => line.startsWith('data:'))
          .map(line => line.slice(5).replace(/^\s+/, '').replace(/\r$/, ''))
          .join('')
        if (!dataStr) continue
        try {
          const evt = JSON.parse(dataStr)
          if (evt.type === 'token') {
            aiMsg.value.content += evt.content || evt.data || ''
            scrollBottom()
          } else if (evt.type === 'done') {
            aiMsg.value.done = true
            appDone = true   // 应用层结束 → 主动 break，不等 TCP 关闭
          } else if (evt.type === 'error') {
            aiMsg.value.content += '\n\n⚠ ' + (evt.message || evt.data?.message || 'error')
            aiMsg.value.done = true
            appDone = true
          } else if (evt.type === 'fallback') {
            fallback = true
            aiMsg.value.fallback = true
          }
          if (evt.isLlmFallback === true) {
            aiMsg.value.fallback = true
          }
        } catch (e) {
          // JSON 解析失败：打印日志便于排查，但不要把残帧塞回 buf（会造成死循环）
          console.warn('[chat] SSE 帧解析失败', dataStr.slice(0, 120), e)
        }
        if (appDone) break
      }
      if (appDone) reader.cancel().catch(() => {})
    }
    clearTimeout(overallTimeout)
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
/* 布局账目：
   - el-header 高 60px（Element Plus 默认）
   - el-main padding 上下各 20px
   - chat-page 直接放在 main 里，所以可用高 = 100vh - 60 - 40 = 100vh - 100px
   之前写 100vh-60px 导致 chat-page 比可见区域高 40px，输入框被裁掉。 */
.chat-page {
  display: flex;
  height: calc(100vh - 100px);
  background: #fff;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(21,32,71,.05);
}

/* 会话侧栏 */
.sidebar { width: 250px; border-right: 1px solid #eef1f6; display: flex; flex-direction: column; background: #fafbfd; }
.side-head { padding: 12px; border-bottom: 1px solid #eef1f6; }
.side-list { flex: 1; overflow: hidden; }
.side-item { padding: 10px 14px; margin: 4px 8px; border-radius: 8px; cursor: pointer; transition: background .2s; }
.side-item:hover { background: #eef1fe; }
.side-item.active { background: #eef1fe; border-left: 3px solid #4361ee; padding-left: 11px; }
.side-item .title { font-weight: 500; font-size: 13.5px; color: #2b3245; margin-bottom: 3px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.side-item .meta { font-size: 12px; color: #97a1b5; }

/* 消息区 */
.main { flex: 1; display: flex; flex-direction: column; }
.messages { flex: 1; overflow-y: auto; padding: 24px 28px; background: #fff; }
.msg { display: flex; margin-bottom: 20px; }
.msg.user { justify-content: flex-end; }
.avatar { width: 34px; height: 34px; border-radius: 10px; color: #fff; display: flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 600; margin: 0 12px; flex-shrink: 0; }
.avatar-ai { background: linear-gradient(135deg, #4361ee 0%, #7a5cff 100%); box-shadow: 0 3px 8px rgba(67,97,238,.35); }
.msg.user .avatar { order: 1; }
.avatar-user { background: linear-gradient(135deg, #3ba272 0%, #58c08f 100%); }
.bubble { max-width: 72%; background: #f5f7fc; padding: 11px 15px; border-radius: 4px 14px 14px 14px; white-space: pre-wrap; word-wrap: break-word; }
.msg.user .bubble { background: linear-gradient(135deg, #4361ee 0%, #5b7bff 100%); color: #fff; border-radius: 14px 4px 14px 14px; box-shadow: 0 4px 10px rgba(67,97,238,.28); }
.content { font-size: 14px; line-height: 1.65; white-space: pre-wrap; }
.msg.user .content.md:deep(code) { background: rgba(255,255,255,.18); color: #fff; }
.content.md:deep(p) { margin: 4px 0; }
.content.md:deep(pre) { background: #eef1f6; padding: 8px; border-radius: 6px; overflow-x: auto; }
.content.md:deep(code) { background: #eef1f6; padding: 2px 5px; border-radius: 4px; font-size: 12.5px; }
.thinking { color: #97a1b5; }
.fallback-tag { color: #e6a23c; font-size: 12px; margin-top: 8px; padding: 3px 8px; background: #fdf6ec; border-radius: 6px; display: inline-block; }
.stream-cursor { animation: blink 1s infinite; }

/* 空状态 */
.empty { text-align: center; color: #97a1b5; margin-top: 72px; }
.empty-icon { font-size: 44px; margin-bottom: 14px; }
.empty-title { font-size: 17px; font-weight: 600; color: #2b3245; margin-bottom: 6px; }
.empty-sub { font-size: 13px; margin-bottom: 18px; }
.example-tags { display: flex; flex-wrap: wrap; justify-content: center; gap: 8px; max-width: 520px; margin: 0 auto; }
.example-tag { cursor: pointer; padding: 6px 12px; height: auto; border-radius: 16px; transition: all .2s; }
.example-tag:hover { background: #eef1fe; color: #4361ee; border-color: #bcc4f9; }

/* 输入区 */
.input-row { padding: 14px 20px; border-top: 1px solid #eef1f6; display: flex; align-items: flex-end; gap: 10px; background: #fff; }
.send-btn { height: 40px; padding: 0 22px; flex-shrink: 0; }

@keyframes blink { 0%,50% { opacity: 1 } 51%,100% { opacity: 0 } }
</style>
