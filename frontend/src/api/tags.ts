import { apiGet, apiSend } from './client'
import type { TagSummary, TaggedTarget } from './types'

/** 标签作用的目标类型；视频与图片各自的路径前缀在这里拼一次，组件里不出现路径字符串。 */
export type TargetKind = 'video' | 'image'

const targetPath = (kind: TargetKind, id: number): string =>
  kind === 'video' ? `/api/video/items/${id}` : `/api/image/nodes/${id}`

// 标签本身：列出对所有登录用户开放，建/删限 ADMIN（后端 TagController 上的
// @PreAuthorize("hasRole('ADMIN')")）——非 ADMIN 调用 createTag/deleteTag 会收到 403，
// 由调用方（TagPicker）决定怎么把这个 403 讲给用户听。

export const listTags = (domain: 'VIDEO' | 'IMAGE') =>
  apiGet<TagSummary[]>(`/api/tags?domain=${domain}`)

export const createTag = (domain: 'VIDEO' | 'IMAGE', name: string) =>
  apiSend<TagSummary>('POST', '/api/tags', { domain, name })

export const deleteTag = (id: number) => apiSend<void>('DELETE', `/api/tags/${id}`)

// 条目/节点身上的标签——按 kind 拼到视频或图片的路径下。

export const tagsOf = (kind: TargetKind, id: number) =>
  apiGet<TagSummary[]>(`${targetPath(kind, id)}/tags`)

export const setTags = (kind: TargetKind, id: number, tagIds: number[]) =>
  apiSend<TagSummary[]>('PUT', `${targetPath(kind, id)}/tags`, { tagIds })

export const itemsOfTag = (tagId: number, limit = 50) =>
  apiGet<TaggedTarget[]>(`/api/tags/${tagId}/items?limit=${limit}`)
