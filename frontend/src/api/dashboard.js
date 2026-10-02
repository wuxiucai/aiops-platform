import request from '../utils/request'

export function listTemplates()         { return request.get('/api/dashboard/templates') }
export function getTemplate(id)          { return request.get(`/api/dashboard/template/${id}`) }
export function createTemplate(body)     { return request.post('/api/dashboard/template', body) }
export function saveTemplate(id, body)   { return request.put(`/api/dashboard/template/${id}`, body) }
export function deleteTemplate(id)       { return request.delete(`/api/dashboard/template/${id}`) }
export function saveDefault(id)          { return request.put(`/api/dashboard/template/${id}/default`) }