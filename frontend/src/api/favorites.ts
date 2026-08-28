import { apiGet, apiSend } from './client'
import type { VideoFavoriteEntry, ImageFavoriteEntry } from './types'

/** 收藏作用的目标类型；拼路径的规则与 tags.ts 完全一致，各自独立维护一份，
 *  免得两个模块之间产生一条不必要的相互 import。 */
export type TargetKind = 'video' | 'image'

const targetPath = (kind: TargetKind, id: number): string =>
  kind === 'video' ? `/api/video/items/${id}` : `/api/image/nodes/${id}`

export const favorite = (kind: TargetKind, id: number) =>
  apiSend<void>('PUT', `${targetPath(kind, id)}/favorite`)

export const unfavorite = (kind: TargetKind, id: number) =>
  apiSend<void>('DELETE', `${targetPath(kind, id)}/favorite`)

/**
 * 后端 `GET /api/video/favorites`、`GET /api/image/favorites` 直接把 JPA 实体
 * （`VideoItem`/`ImageNode`）序列化成响应，两个域字段形状并不一致——按 kind 重载，
 * 让调用方（FavoritesView）拿到的是真正贴合各自实体的类型，不是过窄的占位形状。
 */
export function listFavorites(kind: 'video', limit?: number): Promise<VideoFavoriteEntry[]>
export function listFavorites(kind: 'image', limit?: number): Promise<ImageFavoriteEntry[]>
export function listFavorites(
  kind: TargetKind, limit = 50,
): Promise<VideoFavoriteEntry[] | ImageFavoriteEntry[]> {
  return kind === 'video'
    ? apiGet<VideoFavoriteEntry[]>(`/api/video/favorites?limit=${limit}`)
    : apiGet<ImageFavoriteEntry[]>(`/api/image/favorites?limit=${limit}`)
}
