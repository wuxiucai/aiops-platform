import request from '../utils/request'

export function getStatOverview()        { return request.get('/api/dashboard-stat/overview') }
export function getHealthMatrix()        { return request.get('/api/dashboard-stat/health-matrix') }
export function listTemplates()         { return request.get('/api/dashboard/templates') }
export function getTemplate(id)          { return request.get(`/api/dashboard/template/${id}`) }
export function createTemplate(body)     { return request.post('/api/dashboard/template', body) }
export function saveTemplate(id, body)   { return request.put(`/api/dashboard/template/${id}`, body) }
export function deleteTemplate(id)       { return request.delete(`/api/dashboard/template/${id}`) }
export function saveDefault(id)          { return request.put(`/api/dashboard/template/${id}/default`) }
export function moveTemplate(id, groupId) { return request.put(`/api/dashboard/template/${id}/move`, { groupId }) }
export function listLogServices()        { return request.get('/api/dashboard-stat/log-services') }

export function getGroupTree()           { return request.get('/api/dashboard/group/tree') }
export function createGroup(body)        { return request.post('/api/dashboard/group', body) }
export function updateGroup(body)        { return request.put('/api/dashboard/group', body) }
export function deleteGroup(id)          { return request.delete(`/api/dashboard/group/${id}`) }
