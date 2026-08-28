import { describe, it, expect, vi } from 'vitest'
import { TicketCache } from '@/api/ticket'

const ticketAt = (expiresAtMs: number, value = 't') => ({ ticket: value, expiresAt: expiresAtMs })

describe('TicketCache', () => {
  it('第一次取会调用签发器', async () => {
    const fetcher = vi.fn().mockResolvedValue(ticketAt(1_000_000, 'first'))
    const cache = new TicketCache(fetcher)

    expect(await cache.get(0)).toBe('first')
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('未过期时复用，不重复签发', async () => {
    const fetcher = vi.fn().mockResolvedValue(ticketAt(1_000_000, 'first'))
    const cache = new TicketCache(fetcher)

    await cache.get(0)
    await cache.get(500_000)
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('进入安全边界内就提前续签', async () => {
    // 不能等真过期：一次拖动会连发十几个 Range 请求，
    // 中间任何一个撞上过期就是一次可见的播放中断
    const fetcher = vi.fn()
      .mockResolvedValueOnce(ticketAt(100_000, 'first'))
      .mockResolvedValueOnce(ticketAt(200_000, 'second'))
    const cache = new TicketCache(fetcher, 30_000)

    await cache.get(0)
    expect(await cache.get(80_000)).toBe('second')
    expect(fetcher).toHaveBeenCalledTimes(2)
  })

  it('并发取票只签发一次', async () => {
    // <video> 与页面上十几张 <img> 会在同一帧里一起要票据
    let resolve: (v: unknown) => void = () => {}
    const fetcher = vi.fn().mockReturnValue(new Promise((r) => { resolve = r }))
    const cache = new TicketCache(fetcher)

    const all = Promise.all([cache.get(0), cache.get(0), cache.get(0)])
    resolve(ticketAt(1_000_000, 'only'))

    expect(await all).toEqual(['only', 'only', 'only'])
    expect(fetcher).toHaveBeenCalledTimes(1)
  })

  it('invalidate 之后强制重新签发', async () => {
    const fetcher = vi.fn()
      .mockResolvedValueOnce(ticketAt(1_000_000, 'first'))
      .mockResolvedValueOnce(ticketAt(1_000_000, 'second'))
    const cache = new TicketCache(fetcher)

    await cache.get(0)
    cache.invalidate()
    expect(await cache.get(0)).toBe('second')
  })

  it('签发失败时不缓存失败结果', async () => {
    const fetcher = vi.fn()
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce(ticketAt(1_000_000, 'recovered'))
    const cache = new TicketCache(fetcher)

    await expect(cache.get(0)).rejects.toThrow('boom')
    expect(await cache.get(0)).toBe('recovered')
  })
})
