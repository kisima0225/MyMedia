import { ref } from 'vue'
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

/**
 * 最近一次成功签发/续签的票据，响应式。
 *
 * 同步版本的 assetUrl() 只能读它——但它不是一张「模块初始化时拍下的快照」：
 * 下面的 issue() 是**唯一**一条通往 cache.get() 的路径，任何一次
 * mediaUrl()/assetUrlAsync()/refreshTicket() 拿到新票据都会顺手写回这里。
 * TicketCache 会在距过期 30 秒时自动续签，续签结果因此也能被这里看到，
 * 不会出现「<Cover> 一直挂着一张几小时前的过期票据打 401」。
 *
 * 是 ref 而不是普通变量：<Cover> 的 src 是个 computed，它在求值时经
 * assetUrl() 读到这个 ref 就自动订阅了——票据到手后封面会自己重新渲染，
 * 不需要用户手动刷新页面。
 */
const latestTicket = ref('')

/** 所有取票路径的唯一入口：拿到票就写回 latestTicket。 */
async function issue(): Promise<string> {
  const ticket = await cache.get()
  latestTicket.value = ticket
  return ticket
}

/** 票据失效时（换用户、401）调它，下次取会重新签发。 */
export function invalidateTicket(): void {
  cache.invalidate()
  // 同步清掉响应式副本：换用户之后 <Cover> 绝不能继续拿前一个用户的票据去取图。
  latestTicket.value = ''
}

/**
 * 主动预热一张票据，给 assetUrl() 这种同步调用方用。
 *
 * 登录成功后由 auth store 调一次：从 /login 正常登录时，本模块早在还没有
 * 凭证的时候就初始化完了（下面那次预取被 hasCredential() 挡掉），没有这一
 * 下主动预热，整个页面生命周期里 assetUrl() 都会返回空串，所有封面永远是占位图。
 *
 * 不会 reject——失败只留一条日志，assetUrl() 继续返回空串、<Cover> 渲染占位。
 */
export function refreshTicket(): Promise<void> {
  return issue().then(() => undefined).catch((err) => {
    // 网络抖动等真实失败不该被吞掉：功能上不受影响，但留一条日志方便定位
    // 「明明登录了却一直没有封面」这类问题。
    console.warn('媒体票据预热失败', err)
  })
}

/**
 * 给 <video src> / <img src> 用的带票据 URL。
 *
 * 是 async 的，所以模板里不能直接绑——要在 setup 里 await 出来再交给 ref。
 * 这个不便是刻意保留的：它提醒调用方「这里有一次网络往返」。
 */
export async function mediaUrl(path: string): Promise<string> {
  return withTicket(path, await issue())
}

export async function assetUrlAsync(assetId: number): Promise<string> {
  return mediaUrl(`/api/assets/${assetId}`)
}

// 只在已有凭证时才预取：匿名启动（比如刚打开 /login，或从深链被守卫拦下之前
// 那一瞬间）不该打一次注定 401 的 /api/auth/media-ticket——那个 401 会经
// client.ts 的 unauthorizedHandler 触发一次裸的 router.push({ name: 'login' })，
// 时机上可能晚于守卫已经算好的、带 redirect 查询参数的跳转，把它覆盖掉。
//
// 这次预取只覆盖「带着 sessionStorage 里的凭证刷新页面」这一条路径；从 /login
// 正常登录那条路径由 auth store 登录成功后调 refreshTicket() 覆盖。
//
// hasCredential() 读 sessionStorage，在真实浏览器里也不保证总是安全（存储被
// 用户禁用、被沙箱化的 iframe 会直接抛 SecurityError），在 node 里跑的单元测试
// 里则压根没有 sessionStorage 这个全局——client.ts 里的 hasCredential() 自己
// 没有兜这一层，所以这里的 try/catch 是必需的：读取失败就按「没有凭证」处理，
// 反正那种情况下本来就该跳过预取。
let shouldPrefetch = false
try {
  shouldPrefetch = hasCredential()
} catch {
  shouldPrefetch = false
}
if (shouldPrefetch) {
  void refreshTicket()
}

/**
 * 同步版本，给 <Cover> 这种一屏几十个的场景。
 *
 * 用的是**最近一次签发**的票据；还没签过时返回空串，由 <Cover> 渲染占位，
 * 票据到手后 latestTicket 变化，<Cover> 的 computed 响应式地补上真正的封面。
 */
export function assetUrl(assetId: number): string {
  return latestTicket.value ? withTicket(`/api/assets/${assetId}`, latestTicket.value) : ''
}
