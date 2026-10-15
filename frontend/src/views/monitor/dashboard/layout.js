/**
 * 自定义大盘布局：后端 layout_config（[{ widgetId, x, y, w, h }]）与前端栅格项（{ i, x, y, w, h }）互转。
 * 栅格 12 列，行高 80px；i 统一为 String(widgetId)。
 */
export const GRID_COLS = 12
export const ROW_HEIGHT = 80

/** 没有位置信息的 widget 的默认落点：两列排布，每个 6×4 */
export function defaultSlot (idx) {
  return { x: (idx % 2) * 6, y: Math.floor(idx / 2) * 4, w: 6, h: 4 }
}

/** 后端可能把 widgets / layoutConfig 以 JSON 字符串返回，统一转成数组 */
function asArray (v) {
  if (Array.isArray(v)) return v
  if (typeof v === 'string') {
    try { return JSON.parse(v || '[]') } catch (e) { return [] }
  }
  return []
}

export function normalizeWidgets (data) {
  return asArray(data?.widgets)
}

/**
 * 模板数据 → 栅格项。
 * - widgets 中有、layoutConfig 中没有的，追加默认位置；
 * - 同一 widgetId 只保留第一条（清理历史上保存出的重复项）。
 * 比较一律用 String：早期版本把 widgetId 存成字符串，而 widget.id 是数字，
 * 类型不一致会让每个 widget 都被判成"无位置"而重复追加（保存一次多一个窗口）。
 */
export function toLayoutItems (data) {
  const widgets = normalizeWidgets(data)
  const lc = asArray(data?.layoutConfig)
  const placed = new Set(lc.map(it => String(it.widgetId)))
  widgets.forEach((w, idx) => {
    if (!placed.has(String(w.id))) lc.push({ widgetId: w.id, ...defaultSlot(idx) })
  })
  const seen = new Set()
  return lc
    .filter(it => {
      const k = String(it.widgetId)
      if (seen.has(k)) return false
      seen.add(k)
      return true
    })
    .map((it, idx) => {
      const slot = defaultSlot(idx)
      return {
        i: String(it.widgetId != null ? it.widgetId : idx),
        x: it.x ?? slot.x,
        y: it.y ?? slot.y,
        w: it.w ?? slot.w,
        h: it.h ?? slot.h
      }
    })
}

/** 栅格项 → layout_config，widgetId 归一化为数字（与 widget.id 同类型） */
export function toLayoutConfig (layout) {
  return layout.map(it => {
    const n = Number(it.i)
    return { widgetId: Number.isNaN(n) ? it.i : n, x: it.x, y: it.y, w: it.w, h: it.h }
  })
}

/** 子项全是绝对定位，容器须显式撑高：最深的 (y + h) 行 × 行高 + 底部留白 */
export function gridHeight (layout) {
  if (!layout.length) return 600
  const maxBottom = Math.max(...layout.map(it => (it.y || 0) + (it.h || 4)))
  return maxBottom * ROW_HEIGHT + 16
}

export function cellStyle (item) {
  return {
    left: `${item.x / GRID_COLS * 100}%`,
    top: `${item.y * ROW_HEIGHT}px`,
    width: `${item.w / GRID_COLS * 100}%`,
    height: `${item.h * ROW_HEIGHT}px`
  }
}
