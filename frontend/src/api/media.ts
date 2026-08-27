import { TicketCache } from './ticket'
import { apiSend } from './client'

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
void cache.get().then((t) => { latest = t }).catch(() => {
  // 未登录（尚未签过任何票据）或网络抖动时静默失败：assetUrl() 继续返回空串，
  // 由 <Cover> 渲染占位；不吞掉这个 catch 会在每次未登录时的应用加载中
  // 留下一个未处理的 promise rejection——这里只是补上出口，不改变签发本身的
  // 成功/失败语义（TicketCache 自己的失败-不缓存行为不受影响）。
})
export function assetUrl(assetId: number): string {
  return latest ? withTicket(`/api/assets/${assetId}`, latest) : ''
}
