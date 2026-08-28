import { ApiError } from './client'
import type {
  VideoItemSummary, VideoGroupSummary, VideoFileSummary,
  ImageNodeSummary, ImagePageSummary,
} from './types'

// 分享页专用的一层薄封装：与 client.ts 完全独立，不带 Authorization——
// 分享链接的访客没有账号，令牌本身就是凭证。顺手带上登录凭证会让
// 「这条链接对外人是否有效」变得测不出来，所以这里自己发 fetch。

const TICKET_HEADER = 'X-Share-Ticket'

async function shareFetch<T>(path: string, ticket?: string | null,
                             init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (ticket) headers.set(TICKET_HEADER, ticket)

  const response = await fetch(path, { ...init, headers })
  if (!response.ok) throw new ApiError(response.status, `请求失败（HTTP ${response.status}）`)
  return (await response.json()) as T
}

/** 对应后端 ShareLinkDto.PublicView(LibraryDomain domain, boolean requiresPassword, Instant expiresAt)。 */
export interface ShareDescribe {
  domain: 'VIDEO' | 'IMAGE'
  requiresPassword: boolean
  expiresAt: string | null
}

/** 不需要票据也能调：客户端正是靠它知道「要不要弹密码框」。 */
export const describeShare = (token: string) =>
  shareFetch<ShareDescribe>(`/api/share/${encodeURIComponent(token)}`)

export const unlockShare = (token: string, password: string) =>
  shareFetch<{ ticket: string }>(`/api/share/${encodeURIComponent(token)}/unlock`, null, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password }),
  })

/**
 * 对应 VideoShareController.item() 的返回形状——与 api/video.ts 的 itemDetail()
 * 同款内联匿名类型（项目里没有具名的 ItemDetail 类型）。groups 恒为空数组：
 * 分享视图不给分组，一条链接指向一个条目。
 *
 * ticket 只在链接设了密码、且已经解锁的分支才需要带——无密码链接完全不检查这个头。
 */
export const shareVideoItem = (token: string, ticket?: string | null) =>
  shareFetch<{ item: VideoItemSummary; groups: VideoGroupSummary[]; files: VideoFileSummary[] }>(
    `/api/share/${encodeURIComponent(token)}/video/item`, ticket)

/** 对应 ImageShareController.ShareNodeView(node, children, pages)。 */
export interface ShareImageNode {
  node: ImageNodeSummary
  children: ImageNodeSummary[]
  pages: ImagePageSummary[]
}

/**
 * @param nodeId 省略时就是被分享的那个节点；子树内导航时传子节点 id，
 *               越界（不在分享的子树里）由后端 404。
 */
export const shareImageNode = (token: string, ticket?: string | null, nodeId?: number) =>
  shareFetch<ShareImageNode>(
    `/api/share/${encodeURIComponent(token)}/image/node`
    + (nodeId != null ? `?nodeId=${nodeId}` : ''),
    ticket)
