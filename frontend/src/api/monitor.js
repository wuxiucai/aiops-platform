import request from '../utils/request'

export function getOverview() {
  return request.get('/api/monitor/target/overview')
}

export function queryMetric(data) {
  return request.post('/api/monitor/metric/query', data)
}

export function getMetricDefinitions() {
  return request.get('/api/monitor/metric/definitions')
}

export function pageTarget(params) {
  return request.get('/api/monitor/target/page', { params })
}

export function listTarget() {
  return request.get('/api/monitor/target/list')
}

export function addTarget(data) {
  return request.post('/api/monitor/target', data)
}

export function updateTarget(data) {
  return request.put('/api/monitor/target', data)
}

export function deleteTarget(id) {
  return request.delete(`/api/monitor/target/${id}`)
}

// ========== 监控分组 ==========
export function listGroup() {
  return request.get('/api/monitor/group/list')
}

export function addGroup(data) {
  return request.post('/api/monitor/group', data)
}

export function updateGroup(data) {
  return request.put('/api/monitor/group', data)
}

export function deleteGroup(id) {
  return request.delete(`/api/monitor/group/${id}`)
}

// ========== 采集任务 ==========
export function pageCollectTask(params) {
  return request.get('/api/monitor/collect/task/page', { params })
}

export function addCollectTask(data) {
  return request.post('/api/monitor/collect/task', data)
}

export function updateCollectTask(data) {
  return request.put('/api/monitor/collect/task', data)
}

export function deleteCollectTask(id) {
  return request.delete(`/api/monitor/collect/task/${id}`)
}

export function runCollectTask(id) {
  return request.post(`/api/monitor/collect/task/${id}/run`)
}
