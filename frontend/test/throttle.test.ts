import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createThrottle } from '@/lib/throttle'

describe('createThrottle', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('第一次调用立即执行', () => {
    const fn = vi.fn()
    createThrottle(fn, 5000).call(1)
    expect(fn).toHaveBeenCalledWith(1)
  })

  it('间隔内的重复调用被压掉，只在窗口末尾补发最后一次', () => {
    // <video> 的 timeupdate 每秒烧 4 次。不压的话一部两小时的片子
    // 会往服务器打近三万次进度写入
    const fn = vi.fn()
    const throttle = createThrottle(fn, 5000)

    throttle.call(1)
    throttle.call(2)
    throttle.call(3)
    expect(fn).toHaveBeenCalledTimes(1)

    vi.advanceTimersByTime(5000)
    expect(fn).toHaveBeenCalledTimes(2)
    expect(fn).toHaveBeenLastCalledWith(3)
  })

  it('flush 立刻补发挂起的调用', () => {
    // 用户关标签页/切走时必须立刻把最后的进度送出去
    const fn = vi.fn()
    const throttle = createThrottle(fn, 5000)

    throttle.call(1)
    throttle.call(2)
    throttle.flush()
    expect(fn).toHaveBeenCalledTimes(2)
    expect(fn).toHaveBeenLastCalledWith(2)
  })

  it('没有挂起调用时 flush 什么都不做', () => {
    const fn = vi.fn()
    const throttle = createThrottle(fn, 5000)
    throttle.call(1)
    throttle.flush()
    throttle.flush()
    expect(fn).toHaveBeenCalledTimes(1)
  })

  it('cancel 丢掉挂起的调用', () => {
    const fn = vi.fn()
    const throttle = createThrottle(fn, 5000)
    throttle.call(1)
    throttle.call(2)
    throttle.cancel()
    vi.advanceTimersByTime(10_000)
    expect(fn).toHaveBeenCalledTimes(1)
  })
})
