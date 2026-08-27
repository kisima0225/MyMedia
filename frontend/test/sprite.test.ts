import { describe, it, expect } from 'vitest'
import { parseVtt, cueAt } from '@/lib/sprite'

// 计划 05 的 WebVttWriter 产出的形状：每条 cue 指向同一张图的不同 xywh 片段
const VTT = `WEBVTT

00:00:00.000 --> 00:00:12.000
sprite.jpg#xywh=0,0,160,90

00:00:12.000 --> 00:00:24.000
sprite.jpg#xywh=160,0,160,90

00:00:24.000 --> 00:00:36.000
sprite.jpg#xywh=320,0,160,90
`

describe('parseVtt', () => {
  it('解析出全部 cue 与图块坐标', () => {
    const cues = parseVtt(VTT)
    expect(cues).toHaveLength(3)
    expect(cues[0]).toEqual({ start: 0, end: 12, x: 0, y: 0, w: 160, h: 90 })
    expect(cues[2]).toEqual({ start: 24, end: 36, x: 320, y: 0, w: 160, h: 90 })
  })

  it('忽略 WEBVTT 头与空行', () => {
    expect(parseVtt('WEBVTT\n\n\n')).toEqual([])
  })

  it('跳过缺少 xywh 的 cue 而不是整份失败', () => {
    // 一条坏 cue 不该让整个预览功能消失
    const cues = parseVtt(`WEBVTT

00:00:00.000 --> 00:00:12.000
sprite.jpg

00:00:12.000 --> 00:00:24.000
sprite.jpg#xywh=160,0,160,90
`)
    expect(cues).toHaveLength(1)
    expect(cues[0].x).toBe(160)
  })

  it('对空串与垃圾输入返回空数组', () => {
    expect(parseVtt('')).toEqual([])
    expect(parseVtt('这不是 vtt')).toEqual([])
  })
})

describe('cueAt', () => {
  const cues = parseVtt(VTT)

  it('找到时间点所在的 cue', () => {
    expect(cueAt(cues, 0)?.x).toBe(0)
    expect(cueAt(cues, 11.9)?.x).toBe(0)
    expect(cueAt(cues, 12)?.x).toBe(160)
    expect(cueAt(cues, 30)?.x).toBe(320)
  })

  it('超出末尾时回落到最后一个 cue', () => {
    // 视频时长与雪碧图覆盖范围可能差几帧，末尾不该突然没有预览
    expect(cueAt(cues, 999)?.x).toBe(320)
  })

  it('负数回落到第一个 cue', () => {
    expect(cueAt(cues, -5)?.x).toBe(0)
  })

  it('空数组返回 null', () => {
    expect(cueAt([], 10)).toBeNull()
  })
})
