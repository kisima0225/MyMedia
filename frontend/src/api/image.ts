import { apiGet, apiSend } from './client'
import type { ImageNodeSummary, ContinueReadingEntry, ImagePageSummary } from './types'

// 一层薄封装：每个函数只负责拼路径、标注返回类型，不在这里做任何数据加工——
// 分组、排序、字段裁剪都留给调用方（视图/组件），这里保持可以一眼看穿。

// 无需带 libraryId：后端在 principal 上下文里已经过滤到当前用户可访问的 IMAGE 域媒体库。
export const listRoots = () => apiGet<ImageNodeSummary[]>('/api/image/nodes')

export const nodeDetail = (id: number) => apiGet<ImageNodeSummary>(`/api/image/nodes/${id}`)

export const pages = (id: number) => apiGet<ImagePageSummary[]>(`/api/image/nodes/${id}/pages`)

// libraryId 必填、后端没有默认值（ImageBrowseController#browse 的 @RequestParam Long libraryId
// 没有 required = false）——省略它会直接 400，与 video.ts 的 browse 是同一条规矩。
// nodeId 省略表示媒体库的顶层。
export const browse = (libraryId: number, nodeId?: number) =>
  apiGet<{ breadcrumb: ImageNodeSummary[]; nodes: ImageNodeSummary[] }>(
    `/api/image/browse?libraryId=${libraryId}${nodeId != null ? `&nodeId=${nodeId}` : ''}`)

export const continueReading = (limit = 20) =>
  apiGet<ContinueReadingEntry[]>(`/api/image/continue-reading?limit=${limit}`)

export const setReadingMode = (id: number, mode: 'AUTO' | 'FORCE_BOOK' | 'FORCE_FOLDER') =>
  apiSend<ImageNodeSummary>('PUT', `/api/image/nodes/${id}/reading-mode`, { mode })

export const recordReadProgress = (nodeId: number, pageIndex: number) =>
  apiSend<void>('PUT', `/api/image/progress/${nodeId}`, { pageIndex })
