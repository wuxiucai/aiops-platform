import request from '../utils/request'

// ========== 日志检索 ==========
export function searchLogs(data) {
  return request.post('/api/log/search', data)
}
export function searchHistogram(data) {
  return request.post('/api/log/search/histogram', data)
}

// ========== 模板 ==========
export function getTemplatePage(params) {
  return request.get('/api/log/template/page', { params })
}
export function updateTemplateStatus(id, status) {
  return request.put(`/api/log/template/${id}/status`, null, { params: { status } })
}
export function getTemplateTrend(id, params) {
  return request.get(`/api/log/template/${id}/trend`, { params })
}
export function getTemplateSamples(id, params) {
  return request.get(`/api/log/template/${id}/samples`, { params })
}
export function explainTemplate(refId) {
  return request.post('/api/log/ai/explain', { scene: 'template_explain', refId })
}

// ========== 异常 ==========
export function getAnomalyPage(params) {
  return request.get('/api/log/anomaly/page', { params })
}
export function claimAnomaly(id) {
  return request.put(`/api/log/anomaly/${id}/claim`)
}
export function resolveAnomaly(id, body) {
  return request.put(`/api/log/anomaly/${id}/resolve`, body)
}
export function falsePositiveAnomaly(id) {
  return request.put(`/api/log/anomaly/${id}/false-positive`)
}
export function explainAnomaly(refId) {
  return request.post('/api/log/ai/explain', { scene: 'anomaly_explain', refId })
}

// ========== 检测规则 ==========
export function getRulePage(params) {
  return request.get('/api/log/rule/page', { params })
}
export function addRule(data) {
  return request.post('/api/log/rule', data)
}
export function updateRule(data) {
  return request.put('/api/log/rule', data)
}
export function deleteRule(id) {
  return request.delete(`/api/log/rule/${id}`)
}
export function toggleRule(id, enabled) {
  return request.put(`/api/log/rule/${id}/toggle`, null, { params: { enabled } })
}

// ========== Drain 解析 ==========
export function parseOnce(data) {
  return request.post('/api/log/drain/parse', data)
}
export function getDrainParams(params) {
  return request.get('/api/log/drain/params', { params })
}
export function updateDrainParams(data) {
  return request.post('/api/log/drain/params', data)
}
