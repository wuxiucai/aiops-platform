import request from '../utils/request'

export function listProvider() {
  return request.get('/api/llm/provider/list')
}

export function addProvider(data) {
  return request.post('/api/llm/provider', data)
}

export function updateProvider(data) {
  return request.put('/api/llm/provider', data)
}

export function deleteProvider(id) {
  return request.delete(`/api/llm/provider/${id}`)
}

export function testProvider(id) {
  return request.post(`/api/llm/provider/${id}/test`)
}

export function setDefaultProvider(id) {
  return request.put(`/api/llm/provider/${id}/default`)
}

/* ================== 提示词管理 ================== */
export function listPrompt()         { return request.get('/api/llm/prompt/list') }
export function getPrompt(id)        { return request.get(`/api/llm/prompt/${id}`) }
export function savePrompt(data)     { return request.put('/api/llm/prompt', data) }

/* ================== AI 场景调用 ================== */
export function alertExplain(alertId)     { return request.get(`/api/ai/scenario/alert-explain/${alertId}`) }
export function rootCause(incidentId)     { return request.get(`/api/ai/scenario/root-cause/${incidentId}`) }
export function incidentReport(incidentId) { return request.get(`/api/ai/report/${incidentId}`) }
export function similarCase(incidentId, topK = 3) {
  return request.post('/api/ai/similar-case', { incidentId, topK })
}
export function kbSyncEmbeddings()        { return request.post('/api/kb/case/sync-embeddings') }

/* ================== M6 NL2DSL（自然语言→ES DSL） ================== */
// source 可选 { datasourceId, indexConfigId }；不传则后端用默认数据源
export function nl2dslGenerate(question, source = {}) {
  return request.post('/api/log/ai/nl2dsl', { question, ...source })
}
export function nl2dslExecute(recordId)   { return request.post('/api/log/ai/nl2dsl/execute', { recordId }) }
export function nlQueryHistory(limit = 20) {
  return request.get('/api/log/nl-query-history', { params: { limit } })
}

/* ================== AI 运维助手会话 ================== */
// 流式对话 /api/ai/chat/stream 走 fetch + SSE，不经 axios，留在 Chat.vue 内
export function listChatSessions()              { return request.get('/api/ai/chat/sessions') }
export function createChatSession(body)         { return request.post('/api/ai/chat/sessions', body) }
export function updateChatSession(id, body)     { return request.put(`/api/ai/chat/sessions/${id}`, body) }
export function deleteChatSession(id)           { return request.delete(`/api/ai/chat/sessions/${id}`) }
export function listChatMessages(sessionId)     { return request.get(`/api/ai/chat/sessions/${sessionId}/messages`) }
export function saveChatMessagesBatch(sessionId, messages) {
  return request.post(`/api/ai/chat/sessions/${sessionId}/messages/batch`, messages)
}
