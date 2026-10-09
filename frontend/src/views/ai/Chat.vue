<template>
  <div class="chat-page">
    <div class="sidebar">
      <div class="side-head">
        <el-button type="primary" size="small" block @click="newChat">
          <el-icon><Plus /></el-icon> 新对话
        </el-button>
      </div>
      <el-scrollbar class="side-list" v-loading="loadingSessions">
        <div
          v-for="(s, i) in sessions" :key="s.id ?? `local-${i}`"
          class="side-item"
          :class="{ active: i === currentSession, editing: editingIndex === i }"
          @click="editingIndex === i ? null : onSwitchSession(i)"
        >
          <!-- 编辑态：内联 input，回车/失焦保存，Esc 取消 -->
          <div v-if="editingIndex === i" class="rename-row" @click.stop>
            <el-input
              ref="renameInputRef"
              v-model="renameDraft"
              size="small"
              maxlength="30"
              placeholder="对话名称"
              @keydown.enter.prevent="confirmRename(i)"
              @keydown.esc.prevent="cancelRename"
              @blur="confirmRename(i)"
            />
          </div>
          <!-- 展示态 -->
          <template v-else>
            <div class="title">{{ s.title || '未命名对话' }}</div>
            <div class="meta">{{ s.messages.length }} 条 · {{ s.updatedAt }}</div>
            <el-icon
              class="rename-btn"
              title="重命名"
              @click.stop="startRename(i)"
            ><EditPen /></el-icon>
            <el-icon
              class="delete-btn"
              title="删除会话"
              @click.stop="onDeleteSession(i)"
            ><Delete /></el-icon>
          </template>
        </div>
        <el-empty v-if="sessions.length === 0" description="暂无对话" :image-size="80"/>
      </el-scrollbar>
    </div>

    <div class="main">
      <!-- 不再用 v-loading 整页蒙层：文字已流式出来的部分不该被盖住。
           改为：AI 气泡内三点动画（未收到首 token）+ 文字末尾闪烁光标（流式中）+ 发送按钮 loading -->
      <div class="messages" ref="msgRef">
        <div v-for="(m, i) in current.messages" :key="i" :class="['msg', m.role]">
          <div class="avatar" :class="m.role === 'user' ? 'avatar-user' : 'avatar-ai'">
            {{ m.role === 'user' ? '我' : 'AI' }}
          </div>
          <div class="bubble">
            <div class="content" v-if="m.role === 'user'">{{ m.content }}</div>
            <!-- AI 已有内容：流式渲染 + 末尾光标（方案 A） -->
            <template v-else-if="m.content">
              <div class="content md" v-html="mdRender(m.content)"></div>
              <span v-if="m.done === false" class="stream-cursor">▌</span>
            </template>
            <!-- AI 尚未收到首 token：三点弹跳动画（方案 B） -->
            <div v-else class="content thinking-dots">
              <span class="dot"></span><span class="dot"></span><span class="dot"></span>
            </div>
            <div v-if="m.fallback" class="fallback-tag">⚠ 系统降级（isLlmFallback）</div>
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
          :disabled="thinking"
          :placeholder="thinking ? 'AI 正在回答…' : '问点什么（支持中文），Ctrl + Enter 发送...'"
          @keydown.ctrl.enter="send"
        />
        <el-button type="primary" round class="send-btn" @click="send" :loading="thinking" :disabled="thinking">
          <el-icon v-if="!thinking" style="margin-right: 4px"><Promotion /></el-icon>{{ thinking ? '回答中' : '发送' }}
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import { Plus, Promotion, EditPen, Delete } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../../utils/request'
import { getToken, removeToken, removeUser } from '../../utils/auth'

const input = ref('')
const thinking = ref(false)
const msgRef = ref(null)
// 会话结构：{ id, title, messages: [{role, content, done, fallback}], updatedAt }
// id 来自后端 chat_session；新建未落库的会话 id=null
const sessions = ref([])
const currentSession = ref(0)
// 兜底空会话，每次返回新对象避免共享字面量被 push 污染
function makeEmptySession () { return { id: null, title: '', messages: [], updatedAt: '', messagesLoaded: true } }
const current = computed(() => sessions.value[currentSession.value] || makeEmptySession())
const loadingSessions = ref(false)

// ============ 历史会话：从后端拉取 ============
async function loadSessions () {
  loadingSessions.value = true
  try {
    const r = await request.get('/api/ai/chat/sessions')
    const list = r || []
    // 标记每个会话的 messages 为空数组（按需再拉）
    sessions.value = list.map(s => ({
      id: s.id,
      title: s.title || '新对话',
      messages: [],
      updatedAt: (s.updateTime || '').substring(0, 16),
      messagesLoaded: false
    }))
    if (sessions.value.length === 0) {
      // 本地占位（首次对话时再落库）
      sessions.value.push({ id: null, title: '新对话', messages: [], updatedAt: '', messagesLoaded: true })
    }
    currentSession.value = 0
    // 默认选中第一个并加载消息
    if (sessions.value[0]?.id) {
      await loadMessages(sessions.value[0])
    }
  } catch (e) { /* 拦截器已弹错误 */ } finally {
    loadingSessions.value = false
  }
}

async function loadMessages (session) {
  if (!session.id || session.messagesLoaded) return
  try {
    const r = await request.get(`/api/ai/chat/sessions/${session.id}/messages`)
    session.messages = (r || []).map(m => ({
      role: m.role, content: m.content, done: true, fallback: false
    }))
    session.messagesLoaded = true
  } catch (e) { /* 拦截器已弹错误 */ }
}

async function ensureSessionPersisted (session) {
  if (session.id) return session.id
  try {
    const r = await request.post('/api/ai/chat/sessions', { title: session.title || '新对话' })
    session.id = r?.id
    return session.id
  } catch (e) {
    return null
  }
}

// ============ 会话重命名 ============
const editingIndex = ref(-1)
const renameDraft = ref('')
const renameInputRef = ref(null)

function startRename (i) {
  editingIndex.value = i
  renameDraft.value = sessions.value[i].title || ''
  nextTick(() => {
    const el = Array.isArray(renameInputRef.value) ? renameInputRef.value[0] : renameInputRef.value
    el?.focus?.()
    el?.select?.()
  })
}
async function confirmRename (i) {
  const v = renameDraft.value.trim()
  if (v) {
    sessions.value[i].title = v
    sessions.value[i].updatedAt = new Date().toISOString().substring(0, 16)
    // 已落库的会话同步到后端
    if (sessions.value[i].id) {
      try {
        await request.put(`/api/ai/chat/sessions/${sessions.value[i].id}`, { title: v })
      } catch (e) { /* 拦截器已弹错误 */ }
    }
  }
  editingIndex.value = -1
  renameDraft.value = ''
}
function cancelRename () {
  editingIndex.value = -1
  renameDraft.value = ''
}

async function onDeleteSession (i) {
  const s = sessions.value[i]
  try {
    await ElMessageBox.confirm(`确定删除会话 "${s.title}"？此操作会删除全部消息。`, '删除会话',
      { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' })
  } catch { return }
  if (s.id) {
    try {
      await request.delete(`/api/ai/chat/sessions/${s.id}`)
    } catch (e) { /* 拦截器已弹错误 */ return }
  }
  sessions.value.splice(i, 1)
  if (sessions.value.length === 0) {
    sessions.value.push({ id: null, title: '新对话', messages: [], updatedAt: '', messagesLoaded: true })
    currentSession.value = 0
  } else {
    currentSession.value = Math.max(0, Math.min(i, sessions.value.length - 1))
  }
}

// 切换会话：拉到该会话就 loadMessages 一次（按需）
function onSwitchSession (i) {
  currentSession.value = i
  const s = sessions.value[i]
  if (s) loadMessages(s)
  scrollBottom()
}

const examples = [
  '上午 cpu 最高的服务是哪个',
  '现在有多少 unresolved 告警',
  '近 1 小时 order-service 的错误日志有什么特点'
]

async function newChat () {
  // 落库：直接在后端创建会话，避免本地占位
  try {
    const r = await request.post('/api/ai/chat/sessions', { title: `对话 ${sessions.value.length + 1}` })
    sessions.value.push({
      id: r?.id,
      title: r?.title || `对话 ${sessions.value.length + 1}`,
      messages: [],
      updatedAt: (r?.updateTime || '').substring(0, 16),
      messagesLoaded: true
    })
  } catch (e) {
    // 后端挂了：仍在前端临时造一个，等 SSE 时再尝试落库
    sessions.value.push({ id: null, title: `对话 ${sessions.value.length + 1}`, messages: [], updatedAt: '', messagesLoaded: true })
  }
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
  // 兜底：极端情况下 sessions=[] / currentSession 越界 → 必须先创建一个真实的会话再 push，
  // 否则消息会写到一个 throwaway 的 empty session 上被丢。
  if (!sessions.value[currentSession.value]) {
    sessions.value.push({ id: null, title: '新对话', messages: [], updatedAt: '', messagesLoaded: true })
    currentSession.value = sessions.value.length - 1
  }
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
    // 落库：把这轮的 user + assistant 消息批量追加到后端
    await persistTurn(q, aiMsg.value.content || '')
  } catch (e) {
    aiMsg.value.content += '\n\n⚠ 连接错误: ' + (e?.message || '未知')
    aiMsg.value.done = true
    // 出错也尝试落库（保留错误信息便于离线复盘）
    try { await persistTurn(q, aiMsg.value.content || '') } catch { /* ignore */ }
  } finally {
    thinking.value = false
  }
}

/**
 * 把这一轮 user 提问 + AI 回答持久化到后端 chat_message。
 * 懒创建：如果当前会话还没 id，先创建会话。
 */
async function persistTurn (userQuestion, assistantReply) {
  const session = current.value
  if (!session) return
  const sid = await ensureSessionPersisted(session)
  if (!sid) return
  try {
    await request.post(`/api/ai/chat/sessions/${sid}/messages/batch`, [
      { role: 'user', content: userQuestion || '' },
      { role: 'assistant', content: assistantReply || '' }
    ])
  } catch (e) { /* 拦截器已弹错误 */ }
}

onMounted(loadSessions)
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
.side-item { padding: 10px 14px; margin: 4px 8px; border-radius: 8px; cursor: pointer; transition: background .2s; position: relative; }
.side-item:hover { background: #eef1fe; }
.side-item.active { background: #eef1fe; border-left: 3px solid #4361ee; padding-left: 11px; }
.side-item.editing { background: #fff; border: 1px solid #bcc4f9; cursor: default; }
.side-item .title { font-weight: 500; font-size: 13.5px; color: #2b3245; margin-bottom: 3px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; padding-right: 48px; }
.side-item .meta { font-size: 12px; color: #97a1b5; }
/* 重命名 + 删除按钮：默认隐藏，悬停浮现 */
.side-item .rename-btn,
.side-item .delete-btn {
  position: absolute;
  font-size: 13px;
  color: #97a1b5;
  opacity: 0;
  transition: opacity .15s, color .15s;
  cursor: pointer;
  padding: 2px;
}
.side-item .rename-btn { top: 10px; right: 28px; }
.side-item .delete-btn { top: 10px; right: 10px; }
.side-item:hover .rename-btn,
.side-item:hover .delete-btn { opacity: 1; }
.side-item .rename-btn:hover { color: #4361ee; }
.side-item .delete-btn:hover { color: #f56c6c; }
/* 编辑态输入框撑满 */
.rename-row { width: 100%; }
.rename-row :deep(.el-input__wrapper) { box-shadow: 0 0 0 1px #4361ee inset; }

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
.fallback-tag { color: #e6a23c; font-size: 12px; margin-top: 8px; padding: 3px 8px; background: #fdf6ec; border-radius: 6px; display: inline-block; }

/* ============ 流式状态视觉反馈 ============ */
/* 方案 A：AI 文字末尾的闪烁光标（流式过程中） */
.stream-cursor {
  display: inline-block;
  margin-left: 2px;
  color: #4361ee;
  font-weight: 700;
  animation: chat-cursor-blink 0.9s steps(2, start) infinite;
  user-select: none;
}
@keyframes chat-cursor-blink {
  0%, 49% { opacity: 1; }
  50%, 100% { opacity: 0; }
}

/* 方案 B：AI 还没收到首 token 时气泡内的三点弹跳动画 */
.thinking-dots {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 2px;
  min-height: 22px;
}
.thinking-dots .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #97a1b5;
  animation: chat-dot-bounce 1.2s infinite ease-in-out;
}
.thinking-dots .dot:nth-child(1) { animation-delay: 0s; }
.thinking-dots .dot:nth-child(2) { animation-delay: 0.18s; }
.thinking-dots .dot:nth-child(3) { animation-delay: 0.36s; }
@keyframes chat-dot-bounce {
  0%, 60%, 100% { transform: translateY(0);    opacity: 0.5; }
  30%           { transform: translateY(-5px); opacity: 1; }
}

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
