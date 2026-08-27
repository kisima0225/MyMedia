import { apiGet, apiSend } from './client'

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
 * （`VideoItem`/`ImageNode`）序列化成响应，两个域字段形状并不一致——这里只标注
 * 调用方（ItemDetailView 判断"是否已收藏"）真正用到、且两个实体都保证有的 `id` 字段。
 * 需要展示收藏列表的任务应该在那时候把这个响应形状钉死成真正的 DTO。
 */
export const listFavorites = (kind: TargetKind, limit = 50) =>
  apiGet<{ id: number }[]>(
    kind === 'video' ? `/api/video/favorites?limit=${limit}` : `/api/image/favorites?limit=${limit}`)
