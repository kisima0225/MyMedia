import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

// media.ts 在模块初始化时会读一次 hasCredential() 决定要不要预取票据。
// 这里把它钉成 false：预取一旦发生，「还没有票据时 assetUrl 返回空串」这条
// 断言就成了竞态。apiSend 也必须挡掉——单测环境里没有真的后端。
const mocks = vi.hoisted(() => ({
  apiSend: vi.fn(),
  hasCredential: vi.fn(() => false),
}))
vi.mock('@/api/client', () => ({
  apiSend: mocks.apiSend,
  hasCredential: mocks.hasCredential,
}))

const { withTicket, assetUrl, mediaUrl, refreshTicket, invalidateTicket } =
  await import('@/api/media')

/** 后端 /api/auth/media-ticket 的响应形状：expiresAt 是 ISO 字符串。 */
const issued = (ticket: string, ttlMs: number) => ({
  ticket,
  expiresAt: new Date(Date.now() + ttlMs).toISOString(),
})

describe('withTicket', () => {
  it('给没有查询串的路径加上票据', () => {
    expect(withTicket('/api/video/stream/12', 'abc')).toBe('/api/video/stream/12?ticket=abc')
  })

  it('给已有查询串的路径追加票据', () => {
    expect(withTicket('/api/image/page/3?w=800', 'abc'))
      .toBe('/api/image/page/3?w=800&ticket=abc')
  })

  it('对票据做 URL 编码', () => {
    // 票据是 base64url，本不含 + / =，但编码是防御性的：
    // 将来换签名算法时这里不该成为一个惊喜
    expect(withTicket('/api/assets/1', 'a+b/c=')).toContain('ticket=a%2Bb%2Fc%3D')
  })
})

describe('assetUrl 的票据生命周期', () => {
  beforeEach(() => {
    // 模块状态是跨用例共享的单例（票据缓存 + 那个响应式副本），每个用例
    // 都从「一张票都没有」开始。
    invalidateTicket()
    mocks.apiSend.mockReset()
  })

  afterEach(() => {
    invalidateTicket()
  })

  it('没票据时返回空串，拿到票据后返回带票据的 URL，作废后又变回空串', async () => {
    mocks.apiSend.mockResolvedValue(issued('tk-1', 900_000))

    // 首屏：还没签过票，<Cover> 该渲染占位而不是一个必然 401 的 <img src>
    expect(assetUrl(7)).toBe('')

    // 任何一条取票路径拿到票，同步版本立刻就能用——latestTicket 不是
    // 「模块初始化时拍下的快照」
    await mediaUrl('/api/video/stream/1')
    expect(assetUrl(7)).toBe('/api/assets/7?ticket=tk-1')

    // 换用户 / 收到 401 之后绝不能继续用旧票据
    invalidateTicket()
    expect(assetUrl(7)).toBe('')
  })

  it('refreshTicket 预热之后同步版本立刻可用（登录后的封面靠这条）', async () => {
    mocks.apiSend.mockResolvedValue(issued('tk-login', 900_000))

    expect(assetUrl(42)).toBe('')
    await refreshTicket()
    expect(assetUrl(42)).toBe('/api/assets/42?ticket=tk-login')
  })

  it('续签之后同步版本跟着换成新票据，不会一直挂着过期的那张', async () => {
    // 第一张票 10 秒后过期，落在 TicketCache 的 30 秒续签边界内，
    // 下一次取票必定重新签发
    mocks.apiSend
      .mockResolvedValueOnce(issued('tk-old', 10_000))
      .mockResolvedValueOnce(issued('tk-new', 900_000))

    await refreshTicket()
    expect(assetUrl(3)).toBe('/api/assets/3?ticket=tk-old')

    await mediaUrl('/api/image/page/3')
    expect(assetUrl(3)).toBe('/api/assets/3?ticket=tk-new')
  })

  it('预热失败时不抛出，assetUrl 保持空串', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    mocks.apiSend.mockRejectedValue(new Error('boom'))

    await expect(refreshTicket()).resolves.toBeUndefined()
    expect(assetUrl(9)).toBe('')
    expect(warn).toHaveBeenCalled()
    warn.mockRestore()
  })
})
