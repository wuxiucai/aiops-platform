import request from '../utils/request'

export function listAgents()                  { return request.get('/api/agent/list') }
export function toggleAgent(id)               { return request.put(`/api/agent/${id}/toggle`) }
export function getAgentStatus(targetId)      { return request.get(`/api/agent/status/${targetId}`) }
export function createAgent(body)             { return request.post('/api/agent/create', body) }
export function createAgentWithTarget(body)   { return request.post('/api/agent/create-with-target', body) }
export function getInstallCommand(id)         { return request.get(`/api/agent/install-command/${id}`) }
