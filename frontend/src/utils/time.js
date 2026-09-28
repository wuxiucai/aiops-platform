// 时间工具：统一 yyyy-MM-dd HH:mm:ss
export function formatDateTime(date) {
  if (!date) return ''
  const d = new Date(date)
  const pad = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

export function nowString() {
  return formatDateTime(new Date())
}

export function minutesAgo(minutes) {
  return formatDateTime(new Date(Date.now() - minutes * 60_000))
}
