import { TicketCache } from './ticket'
import { apiSend, hasCredential } from './client'

/** 把票据挂到 URL 上。票据只在三条只读媒体路径上有效，见后端 ADR-008。 */
export function withTicket(path: string, ticket: string): string {
  const sep = path.includes('?') ? '&' : '?'
  return `${path}${sep}ticket=${encodeURIComponent(ticket)}`
}

const cache = new TicketCache(async () => {
  const issued = await apiSend<{ ticket: string; expiresAt: string }>(
    'POST', '/api/auth/media-ticket')
  return { ticket: issued.ticket, expiresAt: Date.parse(issued.expiresAt) }
})

/** 票据失效时（换用户、401）调它，下次取会重新签发。 */
export function invalidateTicket(): void {
  cache.invalidate()
}

/**
 * 给 <video src> / <img src> 用的带票据 URL。
 *
 * 是 async 的，所以模板里不能直接绑——要在 setup 里 await 出来再交给 ref。
 * 这个不便是刻意保留的：它提醒调用方「这里有一次网络往返」。
 */
export async function mediaUrl(path: string): Promise<string> {
  return withTicket(path, await cache.get())
}

export async function assetUrlAsync(assetId: number): Promise<string> {
  return mediaUrl(`/api/assets/${assetId}`)
}

/**
 * 同步版本，给 <Cover> 这种一屏几十个的场景。
 *
 * 用的是**上一次签发**的票据；首屏渲染时若还没签过，返回空串，
 * 由 <Cover> 渲染占位，票据到手后响应式地补上。
 */
let latest = ''
// 只在已有凭证时才预取：匿名启动（比如刚打开 /login，或从深链被守卫拦下之前
// 那一瞬间）不该打一次注定 401 的 /api/auth/media-ticket——那个 401 会经
// client.ts 的 unauthorizedHandler 触发一次裸的 router.push({ name: 'login' })，
// 时机上可能晚于守卫已经算好的、带 redirect 查询参数的跳转，把它覆盖掉。
//
// hasCredential() 本身包了一层 try/catch：读取 sessionStorage 在真实浏览器里
// 也不保证总是安全（存储被用户禁用、被沙箱化的 iframe 会直接抛 SecurityError），
// 不是只有测试环境缺 sessionStorage 这一种情况；读取失败就按「没有凭证」处理，
// 反正那种情况下本来就该跳过预取。
let shouldPrefetch = false
try {
  shouldPrefetch = hasCredential()
} catch {
  shouldPrefetch = false
}
if (shouldPrefetch) {
  void cache.get().then((t) => { latest = t }).catch((err) => {
    // 网络抖动等真实失败不该被吞掉：assetUrl() 会继续返回空串、<Cover> 渲染
    // 占位，功能上不受影响，但留一条日志方便定位「明明登录了却一直没有封面」
    // 这类问题。
    console.warn('媒体票据预取失败', err)
  })
}
export function assetUrl(assetId: number): string {
  return latest ? withTicket(`/api/assets/${assetId}`, latest) : ''
}
