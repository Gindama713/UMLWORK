export function localDateTime(date) {
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 16)
}

export function rangeInfo(start, end, { future = false, maxDays = 0 } = {}) {
  if (!start || !end) return { valid: false, message: '请选择开始与结束时间' }
  const from = new Date(start).getTime()
  const to = new Date(end).getTime()
  if (!Number.isFinite(from) || !Number.isFinite(to)) return { valid: false, message: '请输入有效时间' }
  if (to <= from) return { valid: false, message: '结束时间必须晚于开始时间' }
  if (future && from <= Date.now()) return { valid: false, message: '预约开始时间需晚于现在' }
  if (maxDays && to - from > maxDays * 86_400_000)
    return { valid: false, message: `查询范围最多 ${maxDays} 天` }
  const minutes = Math.ceil((to - from) / 60_000)
  const days = Math.floor(minutes / 1440)
  const hours = Math.floor((minutes % 1440) / 60)
  const rest = minutes % 60
  const duration = [days && `${days}天`, hours && `${hours}小时`, rest && `${rest}分钟`]
    .filter(Boolean).join('') || '不足1分钟'
  return { valid: true, message: `已选择 ${duration}`, duration }
}

export function quickRange(start, preset, { future = false, now = new Date() } = {}) {
  if (typeof preset === 'string' && preset.startsWith('past-')) {
    const hours = Number(preset.slice(5))
    if (![24, 168, 720].includes(hours)) throw new Error('未知统计时段')
    return { start: localDateTime(new Date(now.getTime() - hours * 3_600_000)), end: localDateTime(now) }
  }
  const base = start ? new Date(start) : new Date(now)
  if (Number.isNaN(base.getTime())) throw new Error('请输入有效开始时间')
  if (future && base <= now) {
    base.setTime(now.getTime())
    base.setHours(base.getHours() + 1, 0, 0, 0)
  }
  const end = new Date(base)
  if (preset === 'overnight') {
    end.setDate(end.getDate() + 1)
    end.setHours(8, 0, 0, 0)
  } else if ([1, 2, 4].includes(preset)) {
    end.setHours(end.getHours() + preset)
  } else {
    throw new Error('未知快捷时长')
  }
  return { start: localDateTime(base), end: localDateTime(end) }
}
