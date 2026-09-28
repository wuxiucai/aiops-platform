import request from '../utils/request'

export function getIncidentPage(params) {
  return request.get('/api/incident/page', { params })
}
export function getIncident(id) {
  return request.get(`/api/incident/${id}`)
}
export function resolveIncident(id, body) {
  return request.put(`/api/incident/${id}/resolve`, body)
}
export function addTimeline(id, data) {
  return request.post(`/api/incident/${id}/timeline`, data)
}
