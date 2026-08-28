import { apiGet, apiSend } from './client'
import type { Library } from './types'

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
