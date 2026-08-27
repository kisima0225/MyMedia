import { describe, it, expect } from 'vitest'
import { withTicket } from '@/api/media'

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
