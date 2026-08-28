import { describe, it, expect } from 'vitest'
import { formatDuration, parseTimestamp } from '@/lib/duration'

describe('formatDuration', () => {
  it('一小时以内不显示小时位', () => {
    expect(formatDuration(0)).toBe('00:00')
    expect(formatDuration(59)).toBe('00:59')
    expect(formatDuration(600)).toBe('10:00')
    expect(formatDuration(3599)).toBe('59:59')
  })

  it('满一小时才加上小时位', () => {
    expect(formatDuration(3600)).toBe('1:00:00')
    expect(formatDuration(4567)).toBe('1:16:07')
    expect(formatDuration(36000)).toBe('10:00:00')
  })

  it('负数与非有限值一律回落到零', () => {
    // 播放器在元数据加载完成前会给出 NaN，界面上绝不能出现 "NaN:NaN"
    expect(formatDuration(-1)).toBe('00:00')
    expect(formatDuration(Number.NaN)).toBe('00:00')
    expect(formatDuration(Number.POSITIVE_INFINITY)).toBe('00:00')
  })

  it('小数向下取整', () => {
    expect(formatDuration(59.9)).toBe('00:59')
  })
})

describe('parseTimestamp', () => {
  it('解析 WebVTT 的时间戳', () => {
    expect(parseTimestamp('00:00:00.000')).toBe(0)
    expect(parseTimestamp('00:01:30.500')).toBe(90.5)
    expect(parseTimestamp('01:16:07.000')).toBe(4567)
  })

  it('接受省略小时的两段式', () => {
    expect(parseTimestamp('01:30.000')).toBe(90)
  })

  it('解析不了就返回 NaN，交给调用方决定怎么办', () => {
    expect(parseTimestamp('garbage')).toBeNaN()
    expect(parseTimestamp('')).toBeNaN()
  })
})
