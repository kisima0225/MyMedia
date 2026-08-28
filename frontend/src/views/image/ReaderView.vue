<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { pages, continueReading, recordReadProgress } from '@/api/image'
import { mediaUrl } from '@/api/media'
import { createThrottle } from '@/lib/throttle'
import {
  next, prev, goTo, setMode, visiblePages,
  type ReaderState, type ReaderMode, type ReaderDirection,
} from '@/lib/reader'
import type { ImagePageSummary } from '@/api/types'
import PageView from '@/components/image/PageView.vue'
import ReaderChrome from '@/components/image/ReaderChrome.vue'

// props: true（router/index.ts）把 :id 注入成字符串——路由参数永远是字符串，
// 转数字的责任留在这个视图里，与 PlayerView 的 fileId、NodeBrowseView 的
// nodeId 同一条规矩。
const props = defineProps<{ id: string }>()
const nodeId = computed(() => Number(props.id))

const router = useRouter()

// ── 客户端阅读偏好：mode/direction，与后端 readingMode（AUTO/FORCE_BOOK/
// FORCE_FOLDER，Task 13 已经处理过）是两件不相干的事——那个决定一个节点
// 能不能读，这个只决定读的时候怎么摆页面。长期偏好存 localStorage，不是
// sessionStorage：读日漫的人每次都要切成 rtl 才对，不该每个标签页都要切一遍。
const MODE_KEY = 'mymedia:reader:mode'
const DIRECTION_KEY = 'mymedia:reader:direction'
const VALID_MODES: ReaderMode[] = ['single', 'double', 'continuous']
const VALID_DIRECTIONS: ReaderDirection[] = ['ltr', 'rtl']

function readStoredMode(): ReaderMode {
  try {
    const value = localStorage.getItem(MODE_KEY)
    return (VALID_MODES as string[]).includes(value ?? '') ? (value as ReaderMode) : 'single'
  } catch {
    // localStorage 在部分环境下不可用（隐私模式、被沙箱化的 iframe）——
    // 读不到就当没存过，不该连累阅读器打不开。
    return 'single'
  }
}
function readStoredDirection(): ReaderDirection {
  try {
    const value = localStorage.getItem(DIRECTION_KEY)
    return (VALID_DIRECTIONS as string[]).includes(value ?? '') ? (value as ReaderDirection) : 'ltr'
  } catch {
    return 'ltr'
  }
}
function persistMode(mode: ReaderMode): void {
  try { localStorage.setItem(MODE_KEY, mode) } catch { /* 存不下就下次再问一遍，不是致命的 */ }
}
function persistDirection(direction: ReaderDirection): void {
  try { localStorage.setItem(DIRECTION_KEY, direction) } catch { /* 同上 */ }
}

const status = ref<'loading' | 'ready' | 'error'>('loading')
const error = ref<unknown>(null)
const pageList = ref<ImagePageSummary[]>([])

const state = ref<ReaderState>({
  total: 0, index: 0, mode: readStoredMode(), direction: readStoredDirection(),
})

// 翻页动效需要知道这一次是"往前翻"还是"往后翻"才能决定滑入方向；
// 状态机本身不记这个（next/prev 的返回值只有目的地索引），所以在视图层
// 单独记一笔，仅供 <Transition> 选择动画方向用，不参与任何翻页逻辑判断。
const moveDir = ref<'forward' | 'backward'>('forward')

const visible = computed(() => visiblePages(state.value))
const spreadKey = computed(() => visible.value.join('-'))

// rtl 下"前进"在视觉上是往左滑入——把翻页方向（forward/backward）与阅读
// 方向（ltr/rtl）做一次异或，选出实际该用哪一侧滑入的动画类名。
const transitionName = computed(() => {
  const forward = moveDir.value === 'forward'
  const rtl = state.value.direction === 'rtl'
  return forward !== rtl ? 'to-forward' : 'to-backward'
})

const errorMessage = computed(() => {
  const err = error.value
  if (err instanceof Error) return err.message
  if (typeof err === 'string') return err
  if (err && typeof err === 'object' && 'message' in err) {
    const m = (err as { message?: unknown }).message
    if (typeof m === 'string') return m
  }
  return '未知错误。'
})

// ── 进度上报：比视频的 5 秒短，翻页比连续播放稀疏得多，2 秒不会造成压力。
const report = createThrottle((pageIndex: number) => {
  void recordReadProgress(nodeId.value, pageIndex)
}, 2000)

// ── 预读：当前屏两侧各预读 2 页。只发请求，不做 blob 缓存管理——
// 浏览器的 HTTP 缓存已经在做这件事，前端再存一份只是在和它抢内存。
async function preloadAround(): Promise<void> {
  const vp = visible.value
  if (vp.length === 0) return
  const lo = Math.min(...vp)
  const hi = Math.max(...vp)
  const targets: number[] = []
  for (let d = 1; d <= 2; d++) {
    if (lo - d >= 0) targets.push(lo - d)
    if (hi + d < state.value.total) targets.push(hi + d)
  }
  for (const idx of targets) {
    const page = pageList.value[idx]
    if (!page) continue
    try {
      const url = await mediaUrl(`/api/image/page/${page.id}`)
      new Image().src = url
    } catch {
      // 预读失败静默跳过——它是锦上添花，不是阅读的前提条件
    }
  }
}

// ── 连续滚动模式：用 IntersectionObserver 算"当前页"，不在 scroll 里算。
const continuousRoot = ref<HTMLElement | null>(null)
const pageRefs = ref<(HTMLElement | null)[]>([])
function setPageRef(idx: number, el: HTMLElement | null): void {
  pageRefs.value[idx] = el
}

let observer: IntersectionObserver | null = null
const visibleRatios = new Map<number, number>()

function teardownObserver(): void {
  observer?.disconnect()
  observer = null
  visibleRatios.clear()
}

function setupObserver(): void {
  teardownObserver()
  const root = continuousRoot.value
  if (!root) return
  observer = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      const idx = Number((entry.target as HTMLElement).dataset.index)
      visibleRatios.set(idx, entry.isIntersecting ? entry.intersectionRatio : 0)
    }
    let best = -1
    let bestRatio = 0
    for (const [idx, ratio] of visibleRatios) {
      if (ratio > bestRatio) { bestRatio = ratio; best = idx }
    }
    if (best >= 0) state.value = goTo(state.value, best)
  }, { root, threshold: [0, 0.25, 0.5, 0.75, 1] })
  for (const el of pageRefs.value) {
    if (el) observer.observe(el)
  }
}

async function scrollToCurrentPage(): Promise<void> {
  await nextTick()
  pageRefs.value[state.value.index]?.scrollIntoView({ block: 'start' })
}

async function load(): Promise<void> {
  status.value = 'loading'
  error.value = null
  pageList.value = []
  teardownObserver()
  pageRefs.value = []
  try {
    const id = nodeId.value
    const [pageResult, progress] = await Promise.all([pages(id), continueReading()])
    pageList.value = pageResult

    // continueReading() 是全局的（跨节点）——按 nodeId 找出属于这个节点的那条，
    // 找不到就从头读。0-based 的 pageIndex 与状态机的 index 同一个语义。
    const entry = progress.find((e) => e.nodeId === id)
    const resumeIndex = entry ? entry.pageIndex : 0

    const baseState: ReaderState = {
      total: pageResult.length, index: 0, mode: state.value.mode, direction: state.value.direction,
    }
    state.value = goTo(baseState, resumeIndex)

    status.value = 'ready'
    await nextTick()
    if (state.value.mode === 'continuous') {
      setupObserver()
      await scrollToCurrentPage()
    }
    void preloadAround()
  } catch (err) {
    error.value = err
    status.value = 'error'
  }
}

onMounted(load)
watch(nodeId, load)

watch(() => state.value.index, (idx) => {
  report.call(idx)
  if (state.value.mode !== 'continuous') void preloadAround()
})

watch(() => state.value.mode, async (mode) => {
  if (mode === 'continuous') {
    await nextTick()
    setupObserver()
    await scrollToCurrentPage()
  } else {
    teardownObserver()
  }
})

function advance(): void {
  moveDir.value = 'forward'
  state.value = next(state.value)
}
function retreat(): void {
  moveDir.value = 'backward'
  state.value = prev(state.value)
}
function jump(index: number): void {
  moveDir.value = index >= state.value.index ? 'forward' : 'backward'
  state.value = goTo(state.value, index)
}
function changeMode(mode: ReaderMode): void {
  state.value = setMode(state.value, mode)
  persistMode(mode)
}
function changeDirection(direction: ReaderDirection): void {
  state.value = { ...state.value, direction }
  persistDirection(direction)
}

// ── 按键映射：方向在这里生效，不在状态机里——next 永远是"读下去"，
// 哪个方向键触发它取决于阅读方向，这是一层按键到语义的映射，把它混进
// reader.ts 会让状态机变成一张真值表。
const INTERACTIVE_TAGS = new Set(['INPUT', 'SELECT', 'TEXTAREA', 'BUTTON', 'A'])

function onKey(e: KeyboardEvent): void {
  if (e.key === 'Escape') {
    e.preventDefault()
    router.back()
    return
  }
  if (status.value !== 'ready' || pageList.value.length === 0) return
  if (state.value.mode === 'continuous') return
  // chrome 里的滑块/按钮本身就会响应方向键（原生行为）——这里让开，
  // 否则同一次按键会既挪滑块又翻页。
  const active = document.activeElement
  if (active && INTERACTIVE_TAGS.has(active.tagName)) return

  const forwardKey = state.value.direction === 'rtl' ? 'ArrowLeft' : 'ArrowRight'
  const backwardKey = state.value.direction === 'rtl' ? 'ArrowRight' : 'ArrowLeft'

  if (e.key === forwardKey || e.key === ' ' || e.key === 'PageDown') advance()
  else if (e.key === backwardKey || e.key === 'PageUp') retreat()
  else if (e.key === 'Home') jump(0)
  else if (e.key === 'End') jump(state.value.total - 1)
  else return
  e.preventDefault()
}

// ── 点击翻页：横向三等分，左右各 1/3 翻页（同样按方向映射），中间 1/3
// 切换 chrome 显隐。触屏上点击同样会触发，不用另写手势代码。
function onStageClick(e: MouseEvent): void {
  const el = e.currentTarget as HTMLElement
  const rect = el.getBoundingClientRect()
  const ratio = (e.clientX - rect.left) / rect.width
  const forwardZone: 'left' | 'right' = state.value.direction === 'rtl' ? 'left' : 'right'
  if (ratio < 1 / 3) {
    if (forwardZone === 'left') advance(); else retreat()
  } else if (ratio > 2 / 3) {
    if (forwardZone === 'right') advance(); else retreat()
  } else {
    toggleChrome()
  }
}

// ── chrome 显隐：鼠标静止 2 秒淡出，任意移动淡入；中间点击区手动切换。
// 焦点在 chrome 内部时不淡出这一条由 ReaderChrome 自己的 :focus-within
// CSS 兜底，这里不需要知道焦点在哪。
const chromeVisible = ref(true)
let idleTimer: ReturnType<typeof setTimeout> | null = null

function armIdleTimer(): void {
  if (idleTimer) clearTimeout(idleTimer)
  idleTimer = setTimeout(() => { chromeVisible.value = false }, 2000)
}
function onActivity(): void {
  chromeVisible.value = true
  armIdleTimer()
}
function toggleChrome(): void {
  chromeVisible.value = !chromeVisible.value
  if (chromeVisible.value) armIdleTimer()
  else if (idleTimer) { clearTimeout(idleTimer); idleTimer = null }
}

function onVisibilityChange(): void {
  if (document.hidden) report.flush()
}

onMounted(() => {
  armIdleTimer()
  window.addEventListener('keydown', onKey)
  document.addEventListener('visibilitychange', onVisibilityChange)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey)
  document.removeEventListener('visibilitychange', onVisibilityChange)
  if (idleTimer) clearTimeout(idleTimer)
  teardownObserver()
  report.flush()
})
</script>

<template>
  <div class="reader" @mousemove="onActivity">
    <div v-if="status === 'loading'" class="state-msg">加载中…</div>

    <div v-else-if="status === 'error'" class="state-msg">
      <p>加载失败：{{ errorMessage }}</p>
      <button type="button" class="retry" @click="load">重试</button>
    </div>

    <div v-else-if="pageList.length === 0" class="state-msg">
      <p>这个节点没有可阅读的页面</p>
    </div>

    <template v-else>
      <div v-if="state.mode !== 'continuous'" class="stage" @click="onStageClick">
        <Transition :name="transitionName">
          <div class="spread" :key="spreadKey">
            <PageView
              v-for="idx in visible"
              :key="idx"
              :fileId="pageList[idx].id"
              :alt="`第 ${idx + 1} 页`"
              :eager="true"
              :width="pageList[idx].width"
              :height="pageList[idx].height"
            />
          </div>
        </Transition>
      </div>

      <div v-else ref="continuousRoot" class="continuous">
        <div
          v-for="(page, idx) in pageList"
          :key="page.id"
          class="continuous-page"
          :data-index="idx"
          :ref="(el) => setPageRef(idx, el as HTMLElement | null)"
        >
          <PageView
            :fileId="page.id"
            :alt="`第 ${idx + 1} 页`"
            :eager="idx === state.index"
            :width="page.width"
            :height="page.height"
          />
        </div>
      </div>

      <ReaderChrome
        :state="state"
        :nodeId="nodeId"
        :visible="chromeVisible"
        @next="advance"
        @prev="retreat"
        @goTo="jump"
        @setMode="changeMode"
        @setDirection="changeDirection"
      />
    </template>
  </div>
</template>

<style scoped>
.reader {
  position: relative;
  min-height: 100vh;
  /* 整个应用唯一亮面在这里满幅出现：暗框围着一页亮漫画会产生光晕，
     长时间阅读很难受，这个突变也让"换了一个域"被身体直接感知到。 */
  background: var(--page);
  color: var(--page-ink);
  overflow: hidden;
}

.state-msg {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-3);
  min-height: 100vh;
  padding: var(--space-6);
  text-align: center;
  font-family: var(--font-body);
  font-size: var(--step-0);
  color: var(--page-ink);
}

.retry {
  padding: var(--space-2) var(--space-4);
  border: 1px solid var(--page-ink);
  border-radius: var(--radius);
  background: transparent;
  color: var(--page-ink);
  font-family: var(--font-body);
  font-size: var(--step-0);
  font-weight: 600;
  cursor: pointer;
  transition: border-color var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}

.retry:hover {
  border-color: var(--accent);
  color: var(--accent);
}

.stage {
  position: relative;
  width: 100%;
  height: 100vh;
  overflow: hidden;
  cursor: pointer;
}

.spread {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-4);
  padding: var(--space-5);
}

.to-forward-enter-active,
.to-forward-leave-active,
.to-backward-enter-active,
.to-backward-leave-active {
  transition: transform var(--dur-fast) var(--ease);
}

.to-forward-enter-from {
  transform: translateX(100%);
}
.to-forward-leave-to {
  transform: translateX(-100%);
}
.to-backward-enter-from {
  transform: translateX(-100%);
}
.to-backward-leave-to {
  transform: translateX(100%);
}

.continuous {
  height: 100vh;
  overflow-y: auto;
  scroll-behavior: auto;
}

.continuous-page {
  display: flex;
  justify-content: center;
  padding: var(--space-2) var(--space-5);
}

.continuous-page:last-child {
  /* 留出浮层控制条的高度，最后一页不会被挡住 */
  padding-bottom: var(--space-7);
}
</style>
