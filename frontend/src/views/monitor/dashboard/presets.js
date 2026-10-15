/**
 * 一键模板：从预设场景生成完整大盘，不必从空白开始。
 * widgets(H) 中 H 为一台主机目标的 id；layout 的 idx 指向 widgets 数组下标。
 */
export const PRESETS = {
  duty: {
    name: '值班总览',
    widgets: (H) => [
      { widgetType: 'stat_card', title: '待处理告警', config: { refType: 'alert_pending_count' }, sort: 0 },
      { widgetType: 'stat_card', title: '进行中事件', config: { refType: 'incident_open_count' }, sort: 1 },
      { widgetType: 'top_alert', title: 'Top 告警 (24h)', config: { limit: 8, timeRangeHours: 24 }, sort: 2 },
      { widgetType: 'incident_list', title: '开放事件', config: { status: 'open', limit: 8 }, sort: 3 }
    ],
    layout: [
      { idx: 0, x: 0, y: 0, w: 6, h: 2 },
      { idx: 1, x: 6, y: 0, w: 6, h: 2 },
      { idx: 2, x: 0, y: 2, w: 6, h: 4 },
      { idx: 3, x: 6, y: 2, w: 6, h: 4 }
    ]
  },
  capacity: {
    name: '容量观察',
    widgets: (H) => [
      { widgetType: 'metric_compare', title: '主机 CPU / 内存对比', config: { targetId: H, metricKeys: ['cpu.usage', 'mem.usage'], timeRangeHours: 6 }, sort: 0 },
      { widgetType: 'line_chart', title: 'CPU 使用率趋势', config: { targetId: H, metricKey: 'cpu.usage', timeRangeHours: 24 }, sort: 1 },
      { widgetType: 'line_chart', title: '内存使用率趋势', config: { targetId: H, metricKey: 'mem.usage', timeRangeHours: 24 }, sort: 2 }
    ],
    layout: [
      { idx: 0, x: 0, y: 0, w: 12, h: 5 },
      { idx: 1, x: 0, y: 5, w: 6, h: 5 },
      { idx: 2, x: 6, y: 5, w: 6, h: 5 }
    ]
  },
  postmortem: {
    name: '告警复盘',
    widgets: (H) => [
      { widgetType: 'top_alert', title: '24h 告警分布', config: { limit: 10, timeRangeHours: 24 }, sort: 0 },
      { widgetType: 'incident_list', title: '开放事件列表', config: { status: 'open', limit: 10 }, sort: 1 },
      { widgetType: 'top_template', title: '热点日志模板', config: { limit: 10 }, sort: 2 }
    ],
    layout: [
      { idx: 0, x: 0, y: 0, w: 6, h: 6 },
      { idx: 1, x: 6, y: 0, w: 6, h: 6 },
      { idx: 2, x: 0, y: 6, w: 12, h: 5 }
    ]
  }
}

/**
 * widgets 写入后端才有真实 id：按标题（预设内唯一）把预设位置对应到真实 widgetId。
 * @param defs        preset.widgets(H) 的结果
 * @param realWidgets 保存后重新取回的 widgets
 */
export function presetLayoutConfig (preset, defs, realWidgets) {
  const idByTitle = {}
  realWidgets.forEach(w => { idByTitle[w.title] = w.id })
  return preset.layout
    .map(p => {
      const wid = idByTitle[defs[p.idx]?.title]
      return wid != null ? { widgetId: wid, x: p.x, y: p.y, w: p.w, h: p.h } : null
    })
    .filter(Boolean)
}
