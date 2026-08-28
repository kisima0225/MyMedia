export interface Me { userId: number; username: string; displayName: string; role: 'ADMIN' | 'USER' }
export interface VideoItemSummary {
  id: number; title: string; itemType: 'MOVIE' | 'SERIES' | 'SINGLE_VIDEO' | 'VIDEO_SERIES'
  structure: 'FLAT' | 'GROUPED'; coverAssetId: number | null; libraryId: number
}
/** 对应后端 VideoCatalogDto.GroupSummary——GROUPED 结构（如番剧的季）里的一个分组。 */
export interface VideoGroupSummary { id: number; groupIndex: number; name: string }
export interface VideoFileSummary {
  id: number; groupId: number | null
  role: 'PRIMARY' | 'VERSION' | 'EXTRA' | 'SUBTITLE' | 'TRAILER'
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
  readable: boolean; browsable: boolean; coverAssetId: number | null; libraryId: number
}
/** 对应后端 ImageNodeDto.PageSummary(Long id, int pageIndex, Integer width, Integer height)。 */
export interface ImagePageSummary {
  id: number; pageIndex: number; width: number | null; height: number | null
}
export interface ContinueReadingEntry {
  nodeId: number; nodeTitle: string; coverAssetId: number | null
  pageIndex: number; totalPageCount: number
}
/**
 * 对应后端 ImageSearchHit(Long nodeId, Long libraryId, String name, String title,
 * Long coverAssetId, int totalPageCount, boolean readable, double score)。
 * name 是目录/压缩包原名，一定有；title 是刮削来的，可能为 null——展示时优先 title
 * （后端 record 的注释里写明的规矩），喂给 BookCard 前要做这个兜底与形状映射。
 */
export interface ImageSearchHit {
  nodeId: number
  libraryId: number
  name: string
  title: string | null
  coverAssetId: number | null
  totalPageCount: number
  readable: boolean
  score: number
}
export interface VideoPreviewView {
  videoFileId: number; itemId: number; coverAssetId: number | null; thumbnailAssetId: number | null
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

/** 对应后端 TagDto.Response(Long id, LibraryDomain domain, String name, String slug)。 */
export interface TagSummary {
  id: number
  domain: 'VIDEO' | 'IMAGE'
  name: string
  slug: string
}

/** 对应后端 TagDto.TaggedTarget(Long id, String title, Long coverAssetId)——按标签浏览的一张卡片。 */
export interface TaggedTarget {
  id: number
  title: string
  coverAssetId: number | null
}

/**
 * 对应后端 `GET /api/video/favorites` 直接序列化的 VideoItem 实体（VideoItem.java 的
 * getId/getTitle/getCoverAssetId）。结构上与 VideoCard 的局部 VideoCardItem 类型一致，
 * 可以直接喂给 <VideoCard :item="entry">，不需要映射。
 */
export interface VideoFavoriteEntry {
  id: number
  title: string
  coverAssetId: number | null
}

/**
 * 对应后端 `GET /api/image/favorites` 直接序列化的 ImageNode 实体（ImageNode.java 的
 * getId/getDisplayName/getCoverAssetId/isReadable/isBrowsable/getTotalPageCount/
 * getChildNodeCount）。收藏可以是文件夹（image_favorite 允许收藏任意节点），所以
 * readable/browsable 都是真实值，不是占位。结构上与 BookCard 的局部 BookCardNode
 * 类型一致，可以直接喂给 <BookCard :node="entry">，不需要映射。
 */
export interface ImageFavoriteEntry {
  id: number
  displayName: string
  coverAssetId: number | null
  readable: boolean
  browsable: boolean
  totalPageCount: number
  childNodeCount: number
}

/** 对应后端 ShareLinkDto.Response——两个域的创建端点共用同一份响应形状。 */
export interface ShareLink {
  id: number
  token: string
  domain: 'VIDEO' | 'IMAGE'
  libraryId: number
  targetId: number
  passwordProtected: boolean
  expiresAt: string | null
  createdAt: string
  revokedAt: string | null
}

/** 对应后端 VideoBrowseDto.FolderNode(Long id, String name, int depth, int totalItemCount)。 */
export interface VideoFolderSummary {
  id: number
  name: string
  depth: number
  totalItemCount: number
}

/**
 * 对应后端 VideoBrowseDto.ItemNode(Long id, String title, String itemType, String structure)。
 * 注意没有 coverAssetId——目录树只承载导航，不带元数据（spec §6.3），这是后端设计约束，
 * 不是遗漏。恰好仍满足 VideoCard 的局部 VideoCardItem 类型，直接传即可。
 */
export interface VideoBrowseItemNode {
  id: number
  title: string
  itemType: VideoItemSummary['itemType']
  structure: VideoItemSummary['structure']
}

/** 对应后端 VideoBrowseDto.BrowseResponse(breadcrumb, folders, items)。 */
export interface VideoBrowseResult {
  breadcrumb: VideoFolderSummary[]
  folders: VideoFolderSummary[]
  items: VideoBrowseItemNode[]
}

/**
 * 对应后端 VideoSearchHit(Long itemId, Long libraryId, String title, String sortTitle,
 * Long coverAssetId, double score)。注意 id 字段叫 itemId，不叫 id——传给 VideoCard 前
 * 要自己映射一次。
 */
export interface VideoSearchHit {
  itemId: number
  libraryId: number
  title: string
  sortTitle: string
  coverAssetId: number | null
  score: number
}

/**
 * 对应后端 UploadDto.Response(Long id, String status, boolean instant, String filename,
 * long totalSize, int chunkSize, int totalChunks, List<Integer> receivedChunks,
 * Long scannedFileId, String relativePath, String lastError, Instant completedAt)。
 *
 * **`chunkSize` 由服务端按 `mymedia.upload.chunk-size` 算好下发**——前端切片必须用这个值，
 * 不能自己定义常量，否则分片边界和服务端的 totalChunks/校验对不上（preflight 裁决 R30，
 * 已读 UploadSessionService.create 源码核对）。
 */
export interface UploadSession {
  id: number
  status: 'RECEIVING' | 'ASSEMBLING' | 'COMPLETED' | 'FAILED'
  instant: boolean
  filename: string
  totalSize: number
  chunkSize: number
  totalChunks: number
  receivedChunks: number[]
  scannedFileId: number | null
  relativePath: string | null
  lastError: string | null
  completedAt: string | null
}

/** 对应后端 MetadataDto.CandidateResponse(Long id, String provider, String externalId, String title, Integer year, double score)。 */
export interface ScrapeCandidate {
  id: number
  provider: string
  externalId: string
  title: string
  year: number | null
  score: number
}

/**
 * 对应后端 MetadataDto.QueueEntry(LibraryDomain domain, Long targetId, String title,
 * Long coverAssetId, List<CandidateResponse> candidates)——这是新增端点
 * `GET /api/scrape/queue` 的响应元素（preflight 裁决 R31：既有的
 * `/candidates?domain=&targetId=` 只能查单个目标，没有"全局待确认队列"这回事，
 * 端点是本任务新加的）。
 */
export interface ScrapeQueueEntry {
  domain: 'VIDEO' | 'IMAGE'
  targetId: number
  title: string
  coverAssetId: number | null
  candidates: ScrapeCandidate[]
}

/**
 * 对应后端 MetadataDto.Response(Map<String,String> fields, Map<String,String> fieldSources,
 * Set<String> lockedFields, String scrapeStatus, String scrapeSource, String scrapeSourceId)。
 * `fieldSources`/`scrapeSource` 的取值是 `LocalNfo`/`Bangumi`/`TMDB`/`Filename`/`USER`
 * 这五个精确字符串之一（preflight 裁决 R35，大小写与 brief 原文不同，已读四个
 * MetadataProvider 的 NAME 常量与两处 applyUserEdit 写入代码核对）。
 */
export interface MetadataSnapshot {
  fields: Record<string, string>
  fieldSources: Record<string, string>
  lockedFields: string[]
  scrapeStatus: 'NOT_APPLICABLE' | 'PENDING' | 'MATCHED' | 'NO_MATCH' | 'NEEDS_REVIEW' | 'ERROR'
  scrapeSource: string | null
  scrapeSourceId: string | null
}
