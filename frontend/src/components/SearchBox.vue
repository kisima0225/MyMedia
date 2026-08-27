<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { apiGet } from '@/api/client'
import type { VideoSearchHit } from '@/api/types'

// 建议下拉需要"视频/图片分区，不混排"——唯一能一次给出两个域结果的端点是
// 跨域的 GET /api/search（GlobalSearchController），不是本任务范围内的
// /api/video/search。图片那半边的形状钉在这里，不进 types.ts：它只在这一个
// 下拉里用得到，真正的图片搜索类型留给图片域自己的任务去定义。
interface GlobalImageSearchHit {
  nodeId: number
  libraryId: number
  name: string
  title: string | null
  coverAssetId: number | null
  totalPageCount: number
  readable: boolean
  score: number
}

interface GlobalSearchResponse {
  query: string
  video: VideoSearchHit[]
  image: GlobalImageSearchHit[]
}

const SUGGESTION_LIMIT = 5
const DEBOUNCE_MS = 300

const route = useRoute()
const router = useRouter()

const query = ref('')
const inputEl = ref<HTMLInputElement | null>(null)
const boxEl = ref<HTMLElement | null>(null)
const focused = ref(false)
const open = ref(false)
const suggestions = ref<GlobalSearchResponse | null>(null)

const hasSuggestions = computed(
  () => !!suggestions.value && (suggestions.value.video.length > 0 || suggestions.value.image.length > 0),
)

let requestSeq = 0

async function fetchSuggestions(q: string): Promise<void> {
  const seq = ++requestSeq
  try {
    const result = await apiGet<GlobalSearchResponse>(
      `/api/search?q=${encodeURIComponent(q)}&limit=${SUGGESTION_LIMIT}`)
    if (seq !== requestSeq) return // 有更新的输入已经发出新请求，这份结果作废
    suggestions.value = result
    open.value = focused.value && hasSuggestions.value
  } catch {
    // 建议下拉是锦上添花，失败了安静地不显示就够了，不打断输入、不弹错误。
    if (seq !== requestSeq) return
    suggestions.value = null
    open.value = false
  }
}

let debounceTimer: ReturnType<typeof setTimeout> | null = null

watch(query, (value) => {
  if (debounceTimer) clearTimeout(debounceTimer)
  const trimmed = value.trim()
  if (!trimmed) {
    suggestions.value = null
    open.value = false
    return
  }
  debounceTimer = setTimeout(() => { void fetchSuggestions(trimmed) }, DEBOUNCE_MS)
})

function closeSuggestions(): void {
  open.value = false
}

function onFocus(): void {
  focused.value = true
  if (hasSuggestions.value) open.value = true
}

function onInputBlur(event: FocusEvent): void {
  // 点建议项也会先触发 input 的 blur——用 relatedTarget 判断焦点是不是移到了
  // 下拉内部（RouterLink 本身可聚焦），是的话不关，交给点击本身去处理跳转。
  const next = event.relatedTarget as Node | null
  if (next && boxEl.value?.contains(next)) return
  focused.value = false
  open.value = false
}

function onInputKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    open.value = false
    inputEl.value?.blur()
    return
  }
  if (event.key !== 'Enter') return
  const q = query.value.trim()
  if (!q) return
  closeSuggestions()
  if (event.ctrlKey) {
    // 域内搜索目前只有视频域实现了；其他域按下 Ctrl+Enter 暂时没有效果，
    // 而不是误跳到一个还不存在的域内搜索页。
    if (route.meta.domain === 'video') {
      router.push({ name: 'video-search', query: { q } })
    }
    return
  }
  router.push({ name: 'search', query: { q } })
}

function isTypingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  return target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable
}

// "/" 聚焦搜索框、Esc 失焦——媒体库类应用的通用肌肉记忆。
function onGlobalKeydown(event: KeyboardEvent): void {
  if (event.key !== '/' || isTypingTarget(event.target)) return
  event.preventDefault()
  inputEl.value?.focus()
}

function onDocumentMousedown(event: MouseEvent): void {
  if (boxEl.value?.contains(event.target as Node)) return
  open.value = false
}

onMounted(() => {
  document.addEventListener('keydown', onGlobalKeydown)
  document.addEventListener('mousedown', onDocumentMousedown)
})

onUnmounted(() => {
  document.removeEventListener('keydown', onGlobalKeydown)
  document.removeEventListener('mousedown', onDocumentMousedown)
  if (debounceTimer) clearTimeout(debounceTimer)
})
</script>

<template>
  <div ref="boxEl" class="search-box">
    <input
      ref="inputEl"
      v-model="query"
      type="search"
      class="input"
      placeholder="搜索…（按 / 聚焦，Enter 全局搜索，视频域内 Ctrl+Enter 只搜视频）"
      aria-label="搜索"
      autocomplete="off"
      @focus="onFocus"
      @blur="onInputBlur"
      @keydown="onInputKeydown"
    />

    <div v-if="open && hasSuggestions" class="dropdown" role="listbox">
      <div v-if="suggestions!.video.length" class="section">
        <p class="section-title">视频</p>
        <RouterLink
          v-for="hit in suggestions!.video"
          :key="hit.itemId"
          :to="{ name: 'video-item', params: { id: hit.itemId } }"
          class="suggestion"
          @click="closeSuggestions"
        >
          {{ hit.title }}
        </RouterLink>
      </div>
      <div v-if="suggestions!.image.length" class="section">
        <p class="section-title">图片</p>
        <RouterLink
          v-for="hit in suggestions!.image"
          :key="hit.nodeId"
          :to="{ name: 'image-node', params: { id: hit.nodeId } }"
          class="suggestion"
          @click="closeSuggestions"
        >
          {{ hit.title ?? hit.name }}
        </RouterLink>
      </div>
    </div>
  </div>
</template>

<style scoped>
.search-box {
  position: relative;
  display: flex;
  flex: 1;
  justify-content: center;
}

.input {
  width: 100%;
  max-width: 480px;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--raised);
  color: var(--text);
  font-family: var(--font-body);
  font-size: var(--step--1);
  transition: border-color var(--dur-fast) var(--ease);
}

.input::placeholder {
  color: var(--dim);
}

.input:focus {
  outline: none;
  border-color: var(--accent);
}

.dropdown {
  position: absolute;
  top: calc(100% + var(--space-2));
  width: 100%;
  max-width: 480px;
  padding: var(--space-2);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--raised);
  box-shadow: var(--elevation);
  z-index: 20;
}

.section + .section {
  margin-top: var(--space-2);
  padding-top: var(--space-2);
  border-top: 1px solid var(--line);
}

.section-title {
  padding: var(--space-1) var(--space-2);
  font-size: var(--step--1);
  color: var(--dim);
}

.suggestion {
  display: block;
  padding: var(--space-1) var(--space-2);
  border-radius: var(--radius);
  color: var(--text);
  font-size: var(--step-0);
  text-decoration: none;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  transition: background var(--dur-fast) var(--ease);
}

.suggestion:hover,
.suggestion:focus-visible {
  background: var(--ground);
}
</style>
