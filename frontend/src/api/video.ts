import { apiGet, apiSend } from './client'
import type {
  VideoItemSummary, VideoFileSummary, VideoGroupSummary, ContinueWatchingEntry, VideoPreviewView,
  VideoBrowseResult, VideoSearchHit,
} from './types'

// 一层薄封装：每个函数只负责拼路径、标注返回类型，不在这里做任何数据加工——
// 分组、排序、字段裁剪都留给调用方（视图/组件），这里保持可以一眼看穿。

export const listItems = () => apiGet<VideoItemSummary[]>('/api/video/items')

export const continueWatching = (limit = 20) =>
  apiGet<ContinueWatchingEntry[]>(`/api/video/continue-watching?limit=${limit}`)

export const itemDetail = (id: number) =>
  apiGet<{ item: VideoItemSummary; groups: VideoGroupSummary[]; files: VideoFileSummary[] }>(
    `/api/video/items/${id}`)

export const episodes = (id: number) =>
  apiGet<VideoFileSummary[]>(`/api/video/items/${id}/episodes`)

export const videoPreview = (fileId: number) =>
  apiGet<VideoPreviewView>(`/api/preview/video/${fileId}`)

export const recordProgress = (fileId: number, positionSeconds: number,
                               durationSeconds?: number) =>
  apiSend<void>('PUT', `/api/video/progress/${fileId}`, { positionSeconds, durationSeconds })

// libraryId 必填、后端没有默认值——省略它会直接 400。folderId 省略表示媒体库的顶层，
// 这种情况下 items 恒为空数组（目录树的顶层只有目录，条目挂在具体目录下），由调用方渲染。
export const browse = (libraryId: number, folderId?: number) =>
  apiGet<VideoBrowseResult>(
    `/api/video/browse?libraryId=${libraryId}${folderId != null ? `&folderId=${folderId}` : ''}`)

export const searchVideo = (q: string, limit = 20) =>
  apiGet<VideoSearchHit[]>(`/api/video/search?q=${encodeURIComponent(q)}&limit=${limit}`)
