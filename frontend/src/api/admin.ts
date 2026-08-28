import { apiGet, apiSend, putBinary } from './client'
import type { Library, MetadataSnapshot, ScrapeQueueEntry, UploadSession } from './types'

/**
 * 对应后端 LibraryDto.CreateRequest(String name, LibraryDomain domain, String rootPath)——
 * 只有这三个字段。刮削器不在建库请求体里：建库和配置刮削器是分两步的接口
 * （创建后再调一次 PUT /api/libraries/{id}/metadata-providers），不是一次性提交
 * （已用源码核对 LibraryDto.java，preflight 裁决 5）。
 */
export interface CreateLibraryPayload {
  name: string
  domain: 'VIDEO' | 'IMAGE'
  rootPath: string
}

/**
 * GET /api/libraries：LibraryController.list() 返回当前用户可访问的库
 * （accessService.accessibleLibraries(userId)），不是全表——ADMIN 用户按既有的库
 * 访问逻辑本就能看到全部库，本页面不需要另外处理这一层（preflight 裁决 4）。
 */
export const listLibraries = () => apiGet<Library[]>('/api/libraries')

/** POST /api/libraries——需要 ADMIN，本页面顶栏入口本就只对 isAdmin 显示，不做额外权限分支。 */
export const createLibrary = (payload: CreateLibraryPayload) =>
  apiSend<Library>('POST', '/api/libraries', payload)

export const libraryDetail = (id: number) => apiGet<Library>(`/api/libraries/${id}`)

/** GET /api/libraries/{id}/metadata-providers：返回 List<String>，即刮削器名字列表。 */
export const metadataProviders = (id: number) => apiGet<string[]>(`/api/libraries/${id}/metadata-providers`)

/**
 * PUT /api/libraries/{id}/metadata-providers，请求体对应
 * LibraryDto.MetadataProvidersRequest(@NotNull List<String> providers)。
 * 响应同样是设置后的 List<String>（LibraryController.setMetadataProviders）。
 */
export const setMetadataProviders = (id: number, providers: string[]) =>
  apiSend<string[]>('PUT', `/api/libraries/${id}/metadata-providers`, { providers })

/**
 * 对应 ScanController.requestScan 的响应体 Map.of("jobId", jobId, "status", "ACCEPTED")
 * （HTTP 202）。没有扫描进度端点——不轮询，调用方只把这次调用当作「已提交」处理。
 */
export interface ScanAccepted {
  jobId: number
  status: string
}

/** POST /api/libraries/{id}/scan：无请求体。 */
export const startScan = (id: number) => apiSend<ScanAccepted>('POST', `/api/libraries/${id}/scan`)

// ── 分片上传（UploadDto，preflight 裁决 R30/R34） ──

/** 对应后端 UploadDto.CreateRequest(String filename, long totalSize, String contentHash, Long targetLibraryId)。 */
export interface CreateUploadSessionPayload {
  filename: string
  totalSize: number
  contentHash: string
  targetLibraryId: number
}

export const createUploadSession = (payload: CreateUploadSessionPayload) =>
  apiSend<UploadSession>('POST', '/api/upload/sessions', payload)

/** 断点续传的入口：拿到 receivedChunks 后用 missingChunks() 算出还要补传哪几片。 */
export const getUploadSession = (id: number) => apiGet<UploadSession>(`/api/upload/sessions/${id}`)

/**
 * PUT /api/upload/sessions/{id}/chunks/{index}：请求体是分片本身的原始字节
 * （`application/octet-stream`），走 putBinary() 而不是 apiSend()——
 * apiSend 一律 JSON.stringify body，会把二进制切片写坏（preflight 裁决 R34）。
 */
export const putUploadChunk = (sessionId: number, index: number, chunk: Blob) =>
  putBinary(`/api/upload/sessions/${sessionId}/chunks/${index}`, chunk)

// ── 刮削确认队列 ──

/**
 * GET /api/scrape/queue：新增端点（preflight 裁决 R31），列出当前用户可访问的
 * 全部待确认目标，每个目标带着它自己的候选列表。
 */
export const scrapeQueue = () => apiGet<ScrapeQueueEntry[]>('/api/scrape/queue')

/** POST /api/scrape/candidates/{id}/confirm：确认某一个候选，返回应用后的元数据快照。 */
export const confirmScrapeCandidate = (candidateId: number) =>
  apiSend<MetadataSnapshot>('POST', `/api/scrape/candidates/${candidateId}/confirm`)

/** POST /api/scrape/ignore?domain=&targetId=：整个目标的候选都不是，清空队列并置 NO_MATCH。 */
export const ignoreScrapeCandidates = (domain: 'VIDEO' | 'IMAGE', targetId: number) =>
  apiSend<void>('POST', `/api/scrape/ignore?domain=${domain}&targetId=${targetId}`)

// ── 元数据编辑与字段锁定 ──

export const videoMetadata = (id: number) => apiGet<MetadataSnapshot>(`/api/video/items/${id}/metadata`)

export const editVideoMetadata = (id: number, fields: Record<string, string>) =>
  apiSend<MetadataSnapshot>('PUT', `/api/video/items/${id}/metadata`, { fields })

export const imageMetadata = (id: number) => apiGet<MetadataSnapshot>(`/api/image/nodes/${id}/metadata`)

export const editImageMetadata = (id: number, fields: Record<string, string>) =>
  apiSend<MetadataSnapshot>('PUT', `/api/image/nodes/${id}/metadata`, { fields })
