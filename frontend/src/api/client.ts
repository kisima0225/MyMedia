export class ApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message)
    this.name = 'ApiError'
  }
}

const CREDENTIAL_KEY = 'mymedia.credential'

export function setCredential(username: string, password: string): void {
  // sessionStorage 而不是 localStorage：关掉标签页即失效。
  // 这是一个自托管服务，共用一台电脑的场景比跨设备同步常见得多。
  sessionStorage.setItem(CREDENTIAL_KEY, btoa(`${username}:${password}`))
}

export function clearCredential(): void {
  sessionStorage.removeItem(CREDENTIAL_KEY)
}

export function hasCredential(): boolean {
  return sessionStorage.getItem(CREDENTIAL_KEY) !== null
}

type UnauthorizedHandler = () => void
let unauthorizedHandler: UnauthorizedHandler | null = null

/**
 * 注册 401 时要做的事（清身份、跳转登录页）。
 *
 * 不在这里 import router 或 auth store——那是一条从数据层指向路由层的
 * 依赖。真正的跳转逻辑由 main.ts 注入，client.ts 只负责在恰当的时机调用它。
 */
export function setUnauthorizedHandler(handler: UnauthorizedHandler): void {
  unauthorizedHandler = handler
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const credential = sessionStorage.getItem(CREDENTIAL_KEY)
  const headers: Record<string, string> = {}
  if (credential) headers.Authorization = `Basic ${credential}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'

  const response = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })

  if (!response.ok) {
    // /api/auth/me 的 401 不触发全局跳转——登录页正是靠它的 401 判断密码错了，
    // 一旦跳转，用户永远看不到「用户名或密码不正确」这条消息。
    if (response.status === 401 && path !== '/api/auth/me') {
      unauthorizedHandler?.()
    }
    throw new ApiError(response.status, await errorMessage(response))
  }
  if (response.status === 204 || response.headers.get('Content-Length') === '0') {
    return undefined as T
  }
  const text = await response.text()
  return (text ? JSON.parse(text) : undefined) as T
}

async function errorMessage(response: Response): Promise<string> {
  try {
    const body = (await response.clone().json()) as { message?: string; detail?: string }
    return body.message ?? body.detail ?? `请求失败（HTTP ${response.status}）`
  } catch {
    return `请求失败（HTTP ${response.status}）`
  }
}

export const apiGet = <T>(path: string) => request<T>('GET', path)
export const apiSend = <T>(method: string, path: string, body?: unknown) =>
  request<T>(method, path, body)
