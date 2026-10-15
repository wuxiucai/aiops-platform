import request from '../utils/request'

export function listDatasources()            { return request.get('/api/es/datasource/list') }
export function listIndexConfigs(datasourceId) { return request.get('/api/es/index-config/list', { params: { datasourceId } }) }
