import request from '../utils/request'

export function getEffectMetrics(params)  { return request.get('/api/stat/effect-metrics', { params }) }
export function getLlmCost(params)        { return request.get('/api/stat/llm-cost', { params }) }
