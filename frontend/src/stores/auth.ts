import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { apiGet, setCredential, clearCredential, hasCredential, ApiError } from '@/api/client'
import { invalidateTicket } from '@/api/media'
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
      invalidateTicket()
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
