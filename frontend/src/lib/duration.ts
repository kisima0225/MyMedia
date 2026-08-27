/**
 * 秒 → `mm:ss` 或 `h:mm:ss`。
 *
 * 一小时以内不显示小时位：一部 42 分钟的番剧显示成 `0:42:15` 是在浪费一个字段的宽度。
 * 满一小时之后小时位不补零：`1:16:07` 比 `01:16:07` 更接近人读时间的方式。
 */
export function formatDuration(seconds: number): string {
  const total = Number.isFinite(seconds) && seconds > 0 ? Math.floor(seconds) : 0
  const s = total % 60
  const m = Math.floor(total / 60) % 60
  const h = Math.floor(total / 3600)

  const pad = (n: number) => String(n).padStart(2, '0')
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`
}

/**
 * WebVTT 时间戳 → 秒。接受 `hh:mm:ss.mmm` 与 `mm:ss.mmm` 两种形状。
 *
 * 解析不了返回 `NaN` 而不是 0：0 是一个合法的时间点，用它表示「解析失败」
 * 会让一条坏的 VTT 静默地把所有预览帧堆到视频开头。
 */
export function parseTimestamp(text: string): number {
  const parts = text.trim().split(':')
  if (parts.length < 2 || parts.length > 3) {
    return Number.NaN
  }
  const numbers = parts.map(Number)
  if (numbers.some((n) => !Number.isFinite(n))) {
    return Number.NaN
  }
  return parts.length === 3
    ? numbers[0] * 3600 + numbers[1] * 60 + numbers[2]
    : numbers[0] * 60 + numbers[1]
}
