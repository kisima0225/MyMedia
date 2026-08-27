import { parseTimestamp } from './duration'

export interface SpriteCue {
  readonly start: number
  readonly end: number
  readonly x: number
  readonly y: number
  readonly w: number
  readonly h: number
}

const CUE_TIMING = /^(\S+)\s+-->\s+(\S+)/
const XYWH = /#xywh=(\d+),(\d+),(\d+),(\d+)/

/**
 * 解析计划 05 的 `WebVttWriter` 产出的雪碧图索引。
 *
 * 每条 cue 是「这段时间对应雪碧图上的哪一块」。**一条坏 cue 只丢那一条**，
 * 不让整份索引失效——预览帧是锦上添花，不该因为一个格式问题就整个消失。
 */
export function parseVtt(text: string): SpriteCue[] {
  const cues: SpriteCue[] = []
  const lines = text.split(/\r?\n/)

  for (let i = 0; i < lines.length; i++) {
    const timing = CUE_TIMING.exec(lines[i].trim())
    if (!timing) continue

    const start = parseTimestamp(timing[1])
    const end = parseTimestamp(timing[2])
    const payload = XYWH.exec(lines[i + 1] ?? '')
    if (!payload || Number.isNaN(start) || Number.isNaN(end)) continue

    cues.push({
      start, end,
      x: Number(payload[1]), y: Number(payload[2]),
      w: Number(payload[3]), h: Number(payload[4]),
    })
    i++
  }
  return cues
}

/**
 * 找出某个时间点该显示哪一块。
 *
 * 两端都回落而不是返回 null：视频时长与雪碧图覆盖范围往往差几帧，
 * 拖到最末尾时突然没有预览会显得像坏了。
 */
export function cueAt(cues: SpriteCue[], seconds: number): SpriteCue | null {
  if (cues.length === 0) return null
  if (seconds <= cues[0].start) return cues[0]

  for (const cue of cues) {
    if (seconds >= cue.start && seconds < cue.end) return cue
  }
  return cues[cues.length - 1]
}
