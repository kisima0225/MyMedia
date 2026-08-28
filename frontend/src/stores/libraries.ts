import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { apiGet } from '@/api/client'
import type { Library } from '@/api/types'

export const useLibrariesStore = defineStore('libraries', () => {
  const libraries = ref<Library[]>([])

  const videoLibraries = computed(() => libraries.value.filter((lib) => lib.domain === 'VIDEO'))
  const imageLibraries = computed(() => libraries.value.filter((lib) => lib.domain === 'IMAGE'))

  async function load(): Promise<void> {
    libraries.value = await apiGet<Library[]>('/api/libraries')
  }

  return { videoLibraries, imageLibraries, load }
})
