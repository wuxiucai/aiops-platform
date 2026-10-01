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
