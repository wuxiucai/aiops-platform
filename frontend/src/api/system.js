import request from '../utils/request'

// ========== 用户 ==========
export function pageUser(params) {
  return request.get('/api/system/user/page', { params })
}
export function addUser(data) {
  return request.post('/api/system/user', data)
}
export function updateUser(data) {
  return request.put('/api/system/user', data)
}
export function deleteUser(id) {
  return request.delete(`/api/system/user/${id}`)
}

// ========== 角色 ==========
export function pageRole(params) {
  return request.get('/api/system/role/page', { params })
}
export function allRole() {
  return request.get('/api/system/role/all')
}
export function addRole(data) {
  return request.post('/api/system/role', data)
}
export function updateRole(data) {
  return request.put('/api/system/role', data)
}
export function deleteRole(id) {
  return request.delete(`/api/system/role/${id}`)
}
export function getRolePermissions(id) {
  return request.get(`/api/system/role/${id}/permissions`)
}
export function getUserRoles(id) {
  return request.get(`/api/system/user/${id}/roles`)
}

// ========== 权限树 ==========
export function treePermission() {
  return request.get('/api/system/permission/tree')
}
export function addPermission(data) {
  return request.post('/api/system/permission', data)
}
export function updatePermission(data) {
  return request.put('/api/system/permission', data)
}
export function deletePermission(id) {
  return request.delete(`/api/system/permission/${id}`)
}

// ========== 操作日志 ==========
export function pageOperLog(params) {
  return request.get('/api/system/log/page', { params })
}

// ========== 邮件配置 ==========
export function listMailConfig() {
  return request.get('/api/system/mail-config/list')
}
export function addMailConfig(data) {
  return request.post('/api/system/mail-config', data)
}
export function updateMailConfig(data) {
  return request.put('/api/system/mail-config', data)
}
export function deleteMailConfig(id) {
  return request.delete(`/api/system/mail-config/${id}`)
}
export function setDefaultMailConfig(id) {
  return request.put(`/api/system/mail-config/${id}/default`)
}
export function testMailConfig(id, body) {
  return request.post(`/api/system/mail-config/${id}/test`, body)
}
