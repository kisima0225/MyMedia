import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { apiGet, setCredential, clearCredential, hasCredential, ApiError } from '@/api/client'
import { invalidateTicket, refreshTicket } from '@/api/media'
import type { Me } from '@/api/types'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<Me | null>(null)
  const isAdmin = computed(() => user.value?.role === 'ADMIN')

  /**
   * 登录就是拿凭证请求一次 /api/auth/me——200 说明凭证正确。
   * 后端没有 login 端点，这是 ADR-002 的直接后果。
   */
  async function login(username: string, password: string): Promise<void> {
    setCredential(username, password)
    try {
      user.value = await apiGet<Me>('/api/auth/me')
      // 先作废上一个用户可能留下的票据，再立刻为新身份预热一张。
      //
      // 预热这一步不能省：assetUrl()（<Cover> 用的同步版本）只能读已经签发过的
      // 票据，而从 /login 正常登录时 api/media.ts 初始化那会儿还没有凭证、模块内
      // 那次预取被跳过了。少了这一下，登录后整个页面生命周期里所有封面都是占位图。
      //
      // 不 await：登录的完成与否不该被一次媒体票据往返拖住，失败也只是封面暂时
      // 缺席（refreshTicket 内部已经吞掉异常并记日志）。
      invalidateTicket()
      void refreshTicket()
    } catch (error) {
      clearCredential()
      user.value = null
      if (error instanceof ApiError && error.status === 401) {
        throw new Error('用户名或密码不正确')
      }
      throw error
    }
  }

  /** 刷新页面后从 sessionStorage 里的凭证恢复身份。 */
  async function restore(): Promise<void> {
    if (!hasCredential()) return
    try {
      user.value = await apiGet<Me>('/api/auth/me')
    } catch {
      clearCredential()
      user.value = null
    }
  }

  function logout(): void {
    clearCredential()
    invalidateTicket()
    user.value = null
  }

  return { user, isAdmin, login, restore, logout }
})
