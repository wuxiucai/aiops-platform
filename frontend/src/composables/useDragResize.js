import { onBeforeUnmount } from 'vue'

/**
 * 栅格内的鼠标拖拽移动 + 右下角缩放（绝对定位方案，不依赖第三方栅格库）。
 * item 为 { x, y, w, h } 栅格坐标，拖动过程中就地修改；位移按单元格四舍五入吸附，左右不越出栅格。
 *
 * @param gridEl   栅格容器的模板 ref，用于换算单元格宽度
 * @param options  cols 列数、rowHeight 行高(px)、minW/minH 缩放下限(格)
 */
export function useDragResize (gridEl, { cols = 12, rowHeight = 80, minW = 2, minH = 2 } = {}) {
  let state = null  // { item, mode: 'move' | 'resize', startX, startY, orig }

  function start (mode, item, ev) {
    ev.preventDefault()
    ev.stopPropagation()
    state = { item, mode, startX: ev.clientX, startY: ev.clientY, orig: { x: item.x, y: item.y, w: item.w, h: item.h } }
    document.addEventListener('mousemove', onMove)
    document.addEventListener('mouseup', stop, { once: true })
  }

  function onMove (ev) {
    if (!state || !gridEl.value) return
    const cellW = gridEl.value.getBoundingClientRect().width / cols
    const dx = Math.round((ev.clientX - state.startX) / cellW)
    const dy = Math.round((ev.clientY - state.startY) / rowHeight)
    const next = state.mode === 'move'
      ? snapMove(state.orig, dx, dy, cols)
      : snapResize(state.orig, dx, dy, cols, minW, minH)
    Object.assign(state.item, next)
  }

  function stop () {
    state = null
    document.removeEventListener('mousemove', onMove)
    document.removeEventListener('mouseup', stop)
  }

  // 拖动中途切走路由时，document 上的监听不会自动随组件销毁
  onBeforeUnmount(stop)

  return {
    startMove: (item, ev) => start('move', item, ev),
    startResize: (item, ev) => start('resize', item, ev)
  }
}

/** 平移 (dx, dy) 格后的位置：x 夹在 [0, cols - w]，y 不小于 0 */
export function snapMove (orig, dx, dy, cols) {
  return {
    x: Math.max(0, Math.min(cols - orig.w, orig.x + dx)),
    y: Math.max(0, orig.y + dy)
  }
}

/** 缩放 (dw, dh) 格后的尺寸：不小于下限，右边缘不越出栅格 */
export function snapResize (orig, dw, dh, cols, minW, minH) {
  let w = Math.max(minW, Math.min(cols, orig.w + dw))
  if (orig.x + w > cols) w = cols - orig.x
  return { w, h: Math.max(minH, orig.h + dh) }
}
