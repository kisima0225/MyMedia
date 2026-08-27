import { apiGet, apiSend } from './client'
import type { ShareLink } from './types'

/** 分享作用的目标类型；拼路径规则同 tags.ts / favorites.ts。 */
export type TargetKind = 'video' | 'image'

const targetPath = (kind: TargetKind, id: number): string =>
  kind === 'video' ? `/api/video/items/${id}` : `/api/image/nodes/${id}`

/**
 * 请求体对应后端 `ShareLinkDto.CreateRequest(String password, Integer expiresInDays)`：
 * `expiresInDays` 是"几天后过期"，不是绝对时刻——留空表示永不过期。
 */
export const createShare = (
  kind: TargetKind,
  id: number,
  body: { password?: string; expiresInDays?: number },
) => apiSend<ShareLink>('POST', `${targetPath(kind, id)}/share`, body)

export const listShares = () => apiGet<ShareLink[]>('/api/shares')

export const revokeShare = (id: number) => apiSend<void>('DELETE', `/api/shares/${id}`)
