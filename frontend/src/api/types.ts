export interface Me { userId: number; username: string; displayName: string; role: 'ADMIN' | 'USER' }
export interface VideoItemSummary {
  id: number; title: string; itemType: 'MOVIE' | 'SERIES' | 'SINGLE_VIDEO' | 'VIDEO_SERIES'
  structure: 'FLAT' | 'GROUPED'; coverAssetId: number | null
}
export interface VideoFileSummary {
  id: number; role: 'PRIMARY' | 'VERSION' | 'EXTRA' | 'SUBTITLE' | 'TRAILER'
  episodeIndex: number | null; durationSeconds: number | null
  width: number | null; height: number | null
}
export interface ContinueWatchingEntry {
  fileId: number; itemId: number; itemTitle: string; coverAssetId: number | null
  episodeIndex: number | null; positionSeconds: number
  durationSeconds: number | null; completed: boolean
}
export interface ImageNodeSummary {
  id: number; name: string; displayName: string; depth: number
  sourceKind: 'DIRECTORY' | 'ARCHIVE'; readingMode: 'AUTO' | 'FORCE_BOOK' | 'FORCE_FOLDER'
  directPageCount: number; childNodeCount: number; totalPageCount: number
  readable: boolean; browsable: boolean; coverAssetId: number | null
}
export interface ContinueReadingEntry {
  nodeId: number; nodeTitle: string; coverAssetId: number | null
  pageIndex: number; totalPageCount: number
}
export interface VideoPreviewView {
  videoFileId: number; coverAssetId: number | null; thumbnailAssetId: number | null
  spriteAssetId: number | null; spriteVttAssetId: number | null
}

/** 对应后端 LibraryDto.Response(Long id, String name, LibraryDomain domain, String rootPath, boolean enabled) */
export interface Library {
  id: number
  name: string
  domain: 'VIDEO' | 'IMAGE'
  rootPath: string
  enabled: boolean
}

// 其余（搜索命中、标签、分享、上传会话、刮削候选）在用到它们的任务里补，每个都注明对应的后端 record。
