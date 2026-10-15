import request from '../utils/request'

export function listDatasources()            { return request.get('/api/es/datasource/list') }
export function listIndexConfigs(datasourceId) { return request.get('/api/es/index-config/list', { params: { datasourceId } }) }
export function addDatasource(body)          { return request.post('/api/es/datasource', body) }
export function updateDatasource(body)       { return request.put('/api/es/datasource', body) }
export function deleteDatasource(id)         { return request.delete(`/api/es/datasource/${id}`) }
export function testDatasource(id)           { return request.post(`/api/es/datasource/${id}/test`) }
export function setDefaultDatasource(id)     { return request.put(`/api/es/datasource/${id}/default`) }
export function getDatasourceFields(id, params) { return request.get(`/api/es/datasource/${id}/fields`, { params }) }
export function getDatasourceIndices(id)     { return request.get(`/api/es/datasource/${id}/indices`) }
export function addIndexConfig(body)         { return request.post('/api/es/index-config', body) }
