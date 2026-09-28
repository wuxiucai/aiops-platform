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
