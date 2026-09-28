import request from '../utils/request'

// ========== 告警规则 ==========
export function getRulePage(params) {
  return request.get('/api/alert/rule/page', { params })
}
export function addRule(data) {
  return request.post('/api/alert/rule', data)
}
export function updateRule(data) {
  return request.put('/api/alert/rule', data)
}
export function deleteRule(id) {
  return request.delete(`/api/alert/rule/${id}`)
}
export function toggleRule(id, enabled) {
  return request.put(`/api/alert/rule/${id}/toggle`, null, { params: { enabled } })
}
export function trainRule(id) {
  return request.post(`/api/alert/rule/${id}/train`)
}
export function dryRun(id, data) {
  return request.post(`/api/alert/rule/${id}/dry-run`, data)
}
export function baselineChart(id, params) {
  return request.get(`/api/alert/rule/${id}/baseline-chart`, { params })
}

// ========== 告警记录 ==========
export function getRecordPage(params) {
  return request.get('/api/alert/record/page', { params })
}
export function getRecord(id) {
  return request.get(`/api/alert/record/${id}`)
}
export function claimRecord(id, body) {
  return request.put(`/api/alert/record/${id}/claim`, body)
}
export function resolveRecord(id, body) {
  return request.put(`/api/alert/record/${id}/resolve`, body)
}
export function closeRecord(id, body) {
  return request.put(`/api/alert/record/${id}/close`, body)
}
export function falsePositiveRecord(id, body) {
  return request.put(`/api/alert/record/${id}/false-positive`, body)
}
export function relatedLogs(id) {
  return request.get(`/api/alert/record/${id}/related-logs`)
}
export function count24h() {
  return request.get('/api/alert/record/count-24h')
}

// ========== 静默规则 ==========
export function getSilencePage(params) {
  return request.get('/api/alert/silence/page', { params })
}
export function addSilence(data) {
  return request.post('/api/alert/silence', data)
}
export function updateSilence(data) {
  return request.put('/api/alert/silence', data)
}
export function deleteSilence(id) {
  return request.delete(`/api/alert/silence/${id}`)
}
