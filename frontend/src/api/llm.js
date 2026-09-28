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
