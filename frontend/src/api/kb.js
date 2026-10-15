import request from '../utils/request'

// ========== 故障案例 ==========
// 同步嵌入（POST /api/kb/case/sync-embeddings）已在 llm.js 中以 kbSyncEmbeddings 提供
export function pageCase(params)          { return request.get('/api/kb/case', { params }) }
export function addCase(data)             { return request.post('/api/kb/case', data) }
export function updateCase(data)          { return request.put('/api/kb/case', data) }
export function deleteCase(id)            { return request.delete(`/api/kb/case/${id}`) }

// ========== 知识文档 ==========
export function pageDocument(params)      { return request.get('/api/kb/document', { params }) }
export function getDocument(id)           { return request.get(`/api/kb/document/${id}`) }
export function addDocument(data)         { return request.post('/api/kb/document', data) }
export function updateDocument(data)      { return request.put('/api/kb/document', data) }
export function deleteDocument(id)        { return request.delete(`/api/kb/document/${id}`) }
export function getDocumentChunks(id)     { return request.get(`/api/kb/document/${id}/chunks`) }
export function uploadDocument(formData) {
  return request.post('/api/kb/document/upload', formData, { headers: { 'Content-Type': 'multipart/form-data' } })
}
