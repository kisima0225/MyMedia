<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError } from '@/api/client'
import {
  describeShare, unlockShare, shareVideoItem, shareImageNode,
} from '@/api/share'
import { formatDuration } from '@/lib/duration'
import { next, prev, setMode, visiblePages, type ReaderState, type ReaderMode } from '@/lib/reader'
import type { VideoItemSummary, VideoFileSummary, ImageNodeSummary, ImagePageSummary } from '@/api/types'
import EmptyState from '@/components/common/EmptyState.vue'
import VideoPlayer from '@/components/video/VideoPlayer.vue'
import PageView from '@/components/image/PageView.vue'

// props: true（router/index.ts）把 :token 注入成字符串。
const props = defineProps<{ token: string }>()

const router = useRouter()

type Status = 'loading' | 'not-found' | 'password' | 'content-error' | 'ready'
const status = ref<Status>('loading')

const domain = ref<'VIDEO' | 'IMAGE' | null>(null)
const requiresPassword = ref(false)

// ticket 只在「带密码」分支有意义——无密码链接的所有 GET 都不需要它
// （ShareLinkService.resolveUnlocked 对没设密码的链接完全不检查这个参数）。
const ticket = ref<string | null>(null)
const passwordInput = ref('')
const passwordError = ref<string | null>(null)
const unlocking = ref(false)

// ── 视频分支 ──
const videoItem = ref<VideoItemSummary | null>(null)
const videoFiles = ref<VideoFileSummary[]>([])
const selectedFileId = ref<number | null>(null)

const ROLE_LABEL: Record<VideoFileSummary['role'], string> = {
  PRIMARY: '正片', VERSION: '版本', EXTRA: '花絮', SUBTITLE: '字幕', TRAILER: '预告',
}
const byEpisodeOrder = (a: VideoFileSummary, b: VideoFileSummary): number =>
  (a.episodeIndex ?? Infinity) - (b.episodeIndex ?? Infinity)

function fileLabel(f: VideoFileSummary): string {
  const lead = f.episodeIndex != null ? `E${String(f.episodeIndex).padStart(2, '0')}` : ROLE_LABEL[f.role]
  const dur = f.durationSeconds != null ? formatDuration(f.durationSeconds) : '--:--'
  return `${lead} · ${dur}`
}

// 无密码分享的流地址是完全公开的字符串拼接——不带票据，VideoShareController
// 的 stream 端点对没设密码的链接压根不检查 X-Share-Ticket。
const videoSrc = computed(() => selectedFileId.value != null
  ? `/api/share/${encodeURIComponent(props.token)}/video/stream/${selectedFileId.value}`
  : '')

// ── 图片分支 ──
const imageNode = ref<ImageNodeSummary | null>(null)
const imageChildren = ref<ImageNodeSummary[]>([])
const imagePages = ref<ImagePageSummary[]>([])
// 子树内导航的「上一级」栈：只记祖先 nodeId，不做完整面包屑。
const parentStack = ref<number[]>([])

function pageSrc(fileId: number): string {
  return `/api/share/${encodeURIComponent(props.token)}/image/pages/${fileId}`
}

// 精简阅读状态机：直接用 @/lib/reader，不复用 ReaderView/ReaderChrome——
// 那两个组件硬编码了认证域 API（pages()/continueReading()/recordReadProgress()/
// favorite()），分享访客没有账号，会直接 401 或语义错误。这里只做翻页 + 模式
// 切换，不做收藏、不做阅读进度上报、不做本地存储的模式偏好（匿名会话记了
// 也没意义）。方向固定 ltr——分享页不提供方向切换，是刻意的最小化。
const readerState = ref<ReaderState>({ total: 0, index: 0, mode: 'single', direction: 'ltr' })
const visible = computed(() => visiblePages(readerState.value))

const pageLabel = computed(() => {
  const pages = [...visible.value].sort((a, b) => a - b)
  if (pages.length === 0) return `0 / ${readerState.value.total}`
  const lo = pages[0] + 1
  const hi = pages[pages.length - 1] + 1
  return pages.length > 1 ? `${lo}–${hi} / ${readerState.value.total}` : `${lo} / ${readerState.value.total}`
})

function advance(): void { readerState.value = next(readerState.value) }
function retreat(): void { readerState.value = prev(readerState.value) }
function changeMode(mode: ReaderMode): void { readerState.value = setMode(readerState.value, mode) }

const MODE_OPTIONS: { value: ReaderMode; label: string }[] = [
  { value: 'single', label: '单页' },
  { value: 'double', label: '双页' },
  { value: 'continuous', label: '连续' },
]

// 只在 fetch 成功之后才改 parentStack——失败时栈不该多一条脏记录，
// 否则下一次「上一级」会跟实际展示的节点对不上。
function enterChild(childId: number): void {
  const parentId = imageNode.value?.id
  void runLoad(async () => {
    await loadImageNode(childId)
    if (parentId != null) parentStack.value.push(parentId)
  })
}

function goUpNode(): void {
  const parentId = parentStack.value[parentStack.value.length - 1]
  void runLoad(async () => {
    await loadImageNode(parentId)
    parentStack.value.pop()
  })
}

async function loadImageNode(nodeId: number | undefined): Promise<void> {
  const result = await shareImageNode(props.token, ticket.value, nodeId)
  imageNode.value = result.node
  imageChildren.value = result.children
  imagePages.value = result.pages
  readerState.value = { total: result.pages.length, index: 0, mode: readerState.value.mode, direction: 'ltr' }
}

// 阅读区块自带底部控制条，占满整条底边——分享落款和它会在同一个角落打架，
// 显示阅读器时就不重复渲染落款。
const showReaderBar = computed(() =>
  status.value === 'ready' && !requiresPassword.value
  && domain.value === 'IMAGE' && imagePages.value.length > 0)

// ── 装载 ──
async function loadContent(): Promise<void> {
  if (domain.value === 'VIDEO') {
    const detail = await shareVideoItem(props.token, ticket.value)
    videoItem.value = detail.item
    videoFiles.value = [...detail.files].sort(byEpisodeOrder)
    selectedFileId.value = videoFiles.value[0]?.id ?? null
  } else if (domain.value === 'IMAGE') {
    parentStack.value = []
    await loadImageNode(undefined)
  }
}

// 统一的「装载动作」执行器：把最近一次尝试的动作记下来，失败时进入可重试的
// content-error 态，「重试」按钮统一走 retryContent() 重放同一个动作——
// 初次装载、密码解锁后装载、子节点导航失败后重试，都是同一套机制，不为
// 每个入口各发明一套状态。
let lastLoad: () => Promise<void> = () => loadContent()

async function runLoad(action: () => Promise<void>): Promise<void> {
  lastLoad = action
  status.value = 'loading'
  try {
    await action()
    status.value = 'ready'
  } catch {
    status.value = 'content-error'
  }
}

async function init(): Promise<void> {
  status.value = 'loading'

  // 令牌本身有效与否，和令牌有效之后内容拉取是否成功，是两件不同的事：
  // 前者才是「链接不存在或已失效」，没有重试的必要（重试也不会让撤销的
  // 令牌复活）；后者只是网络抖动一类的临时失败，应该可以重试，不该被误判
  // 成链接失效——这里分成两段 try，不共用同一个 catch。
  try {
    const desc = await describeShare(props.token)
    domain.value = desc.domain
    requiresPassword.value = desc.requiresPassword
  } catch {
    // 无效、过期、已撤销的令牌一律同一句话——不区分是为了不告诉扫链接的人
    // 「这个令牌曾经存在」，与后端 ShareLinkService.resolve 同一条规矩。
    status.value = 'not-found'
    return
  }

  if (requiresPassword.value) {
    status.value = 'password'
    return
  }
  await runLoad(() => loadContent())
}

async function retryContent(): Promise<void> {
  await runLoad(lastLoad)
}

async function submitPassword(): Promise<void> {
  if (unlocking.value) return
  unlocking.value = true
  passwordError.value = null

  // 拆成两段 try：密码错了 vs 密码对了但取内容失败，是两件不同的事——
  // 前者要留在密码框重试，后者已经拿到票据，不该让访客怀疑自己是不是打错了密码。
  let issuedTicket: string
  try {
    const res = await unlockShare(props.token, passwordInput.value)
    issuedTicket = res.ticket
  } catch (err) {
    passwordError.value = err instanceof ApiError && err.status === 401
      ? '密码不正确'
      : '解锁失败，请重试。'
    unlocking.value = false
    return
  }

  ticket.value = issuedTicket
  await runLoad(() => loadContent())
  unlocking.value = false
}

// ── 视觉体系：body 上没有 route.meta.domain（AppShell 只认路由静态 meta），
// 这里按接口实际返回的 domain 手动挂一次，让 --page/--page-ink、--accent
// 等域内令牌生效——图片域的阅读器满幅纸色正是靠这个。离开页面时清掉，
// 交还给下一个路由的 AppShell watchEffect 去决定。
watch(domain, (d) => {
  if (d === 'VIDEO') document.body.dataset.domain = 'video'
  else if (d === 'IMAGE') document.body.dataset.domain = 'image'
}, { immediate: true })

onBeforeUnmount(() => {
  delete document.body.dataset.domain
})

// ── 键盘翻页：仅图片域、无密码、阅读器可见时生效；加成本很低，顺手加上。
const INTERACTIVE_TAGS = new Set(['INPUT', 'SELECT', 'TEXTAREA', 'BUTTON', 'A'])
function onKey(e: KeyboardEvent): void {
  if (domain.value !== 'IMAGE' || requiresPassword.value || status.value !== 'ready') return
  if (readerState.value.mode === 'continuous' || imagePages.value.length === 0) return
  const active = document.activeElement
  if (active && INTERACTIVE_TAGS.has(active.tagName)) return

  if (e.key === 'ArrowRight' || e.key === ' ' || e.key === 'PageDown') advance()
  else if (e.key === 'ArrowLeft' || e.key === 'PageUp') retreat()
  else return
  e.preventDefault()
}

onMounted(() => {
  window.addEventListener('keydown', onKey)
  void init()
})
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <div class="share-view">
    <div v-if="status === 'loading'" class="state-msg">加载中…</div>

    <div v-else-if="status === 'not-found'" class="state-msg">
      <p class="title">这条分享链接不存在或已失效</p>
    </div>

    <div v-else-if="status === 'password'" class="password-screen">
      <form class="card" @submit.prevent="submitPassword">
        <h1 class="title">这条分享链接需要密码</h1>
        <label class="field">
          <span class="field-label">密码</span>
          <input v-model="passwordInput" type="password" autocomplete="off" required autofocus />
        </label>
        <p v-if="passwordError" class="error" role="alert">{{ passwordError }}</p>
        <button type="submit" class="submit" :disabled="unlocking">
          {{ unlocking ? '校验中…' : '解锁' }}
        </button>
      </form>
    </div>

    <div v-else-if="status === 'content-error'" class="state-msg">
      <p class="title">内容加载失败</p>
      <button type="button" class="retry" @click="retryContent">重试</button>
    </div>

    <template v-else-if="status === 'ready'">
      <!-- 带密码 + 已解锁：只显示条目信息与说明，不放媒体——把票据也开一条
           query 参数入口给 <video>/<img>，等于把凭证从头挪到会被转发的 URL 上。 -->
      <div v-if="requiresPassword" class="locked-info">
        <template v-if="domain === 'VIDEO' && videoItem">
          <h1 class="title">{{ videoItem.title }}</h1>
          <ul class="plain-list">
            <li v-for="f in videoFiles" :key="f.id">{{ fileLabel(f) }}</li>
          </ul>
        </template>
        <template v-else-if="domain === 'IMAGE' && imageNode">
          <h1 class="title">{{ imageNode.displayName }}</h1>
          <p class="meta-line">共 {{ imageNode.totalPageCount }} 页，{{ imageNode.childNodeCount }} 个子节点</p>
        </template>
        <p class="notice">这条链接需要密码，请登录后在库内观看。</p>
      </div>

      <!-- 无密码：视频直接播放 -->
      <div v-else-if="domain === 'VIDEO'" class="video-share">
        <EmptyState v-if="videoFiles.length === 0" title="这个条目还没有可播放的文件" />
        <template v-else>
          <h1 v-if="videoItem" class="video-title">{{ videoItem.title }}</h1>
          <VideoPlayer
            v-if="selectedFileId != null"
            :key="selectedFileId"
            :fileId="selectedFileId"
            :src="videoSrc"
            :cues="[]"
            :spriteUrl="null"
            :resumePosition="null"
            :prevTarget="null"
            :nextTarget="null"
          />
          <div v-if="videoFiles.length > 1" class="file-switch">
            <button
              v-for="f in videoFiles"
              :key="f.id"
              type="button"
              class="file-btn"
              :class="{ active: f.id === selectedFileId }"
              @click="selectedFileId = f.id"
            >
              {{ fileLabel(f) }}
            </button>
          </div>
        </template>
      </div>

      <!-- 无密码：图片精简阅读区块 -->
      <div v-else class="image-share">
        <div v-if="imagePages.length > 0" class="reader-stage">
          <div v-if="readerState.mode !== 'continuous'" class="spread">
            <PageView
              v-for="idx in visible"
              :key="idx"
              :fileId="imagePages[idx].id"
              :alt="`第 ${idx + 1} 页`"
              :eager="true"
              :width="imagePages[idx].width"
              :height="imagePages[idx].height"
              :srcOverride="pageSrc(imagePages[idx].id)"
            />
          </div>
          <div v-else class="continuous">
            <div v-for="(page, idx) in imagePages" :key="page.id" class="continuous-page">
              <PageView
                :fileId="page.id"
                :alt="`第 ${idx + 1} 页`"
                :eager="idx === 0"
                :width="page.width"
                :height="page.height"
                :srcOverride="pageSrc(page.id)"
              />
            </div>
          </div>

          <div class="reader-bar">
            <button v-if="parentStack.length > 0" type="button" class="ctrl" @click="goUpNode">上一级</button>
            <button type="button" class="ctrl" @click="router.back()">返回</button>
            <span class="page-label">{{ pageLabel }}</span>
            <button type="button" class="ctrl" :disabled="readerState.mode === 'continuous'" @click="retreat">
              上一页
            </button>
            <button type="button" class="ctrl" :disabled="readerState.mode === 'continuous'" @click="advance">
              下一页
            </button>
            <div class="group" role="group" aria-label="阅读模式">
              <button
                v-for="opt in MODE_OPTIONS"
                :key="opt.value"
                type="button"
                class="ctrl"
                :class="{ active: readerState.mode === opt.value }"
                @click="changeMode(opt.value)"
              >
                {{ opt.label }}
              </button>
            </div>
          </div>
        </div>

        <div v-else-if="imageChildren.length > 0" class="node-list-screen">
          <h1 v-if="imageNode" class="title">{{ imageNode.displayName }}</h1>
          <ul class="node-list">
            <li v-for="c in imageChildren" :key="c.id">
              <button type="button" class="node-btn" @click="enterChild(c.id)">
                <span>{{ c.displayName }}</span>
                <span class="node-count">{{ c.totalPageCount }} 页</span>
              </button>
            </li>
          </ul>
          <button v-if="parentStack.length > 0" type="button" class="retry" @click="goUpNode">上一级</button>
        </div>

        <EmptyState v-else title="这个节点没有可阅读的内容" />
      </div>
    </template>

    <p v-if="!showReaderBar" class="attribution">来自 MyMedia 的分享</p>
  </div>
</template>

<style scoped>
.share-view {
  min-height: 100vh;
  background: var(--ground);
  color: var(--text);
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
}

.title {
  font-family: var(--display);
  font-size: var(--step-1);
  font-weight: 700;
  color: var(--text);
}

.password-screen {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: var(--space-5);
}

.card {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  width: min(360px, 100%);
  padding: var(--space-6) var(--space-5);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--raised);
}

.field {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.field-label {
  font-size: var(--step--1);
  font-weight: 600;
  color: var(--dim);
}

.field input {
  padding: var(--space-3);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--ground);
  color: var(--text);
  font-family: var(--font-body);
  font-size: var(--step-0);
}

.field input:focus-visible {
  border-color: var(--accent);
}

.error {
  font-size: var(--step--1);
  color: var(--text);
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--line);
  border-left: 2px solid var(--accent);
  border-radius: var(--radius);
  background: var(--ground);
}

.submit,
.retry {
  padding: var(--space-3) var(--space-4);
  border: none;
  border-radius: var(--radius);
  background: var(--accent);
  color: var(--shell-ground);
  font-family: var(--font-body);
  font-size: var(--step-0);
  font-weight: 700;
  cursor: pointer;
}

.submit:disabled {
  opacity: 0.6;
  cursor: default;
}

.locked-info {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  max-width: 640px;
  margin: 0 auto;
  padding: var(--space-7) var(--space-5);
}

.notice {
  padding: var(--space-3) var(--space-4);
  border: 1px solid var(--line);
  border-left: 2px solid var(--accent);
  border-radius: var(--radius);
  background: var(--raised);
  color: var(--dim);
  font-size: var(--step-0);
}

.meta-line {
  color: var(--dim);
  font-size: var(--step-0);
}

.plain-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  list-style: none;
  padding: 0;
  color: var(--text);
  font-family: var(--font-data);
  font-size: var(--step--1);
}

.video-share {
  max-width: 1400px;
  margin: 0 auto;
  padding: var(--space-5);
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.video-title {
  font-family: var(--display);
  font-size: var(--step-1);
  font-weight: 700;
}

.file-switch {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.file-btn {
  padding: var(--space-1) var(--space-3);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--raised);
  color: var(--text);
  font-family: var(--font-data);
  font-size: var(--step--1);
  cursor: pointer;
}

.file-btn.active {
  border-color: var(--accent);
  color: var(--accent);
}

/* ── 图片阅读区块：满幅纸色，向阅读器页面（ReaderView）靠拢 ── */
.image-share {
  min-height: 100vh;
  background: var(--page, var(--ground));
  color: var(--page-ink, var(--text));
}

.node-list-screen {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  max-width: 640px;
  margin: 0 auto;
  padding: var(--space-7) var(--space-5);
}

.node-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  list-style: none;
  padding: 0;
}

.node-btn {
  display: flex;
  justify-content: space-between;
  width: 100%;
  padding: var(--space-3) var(--space-4);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--raised);
  color: var(--text);
  font-family: var(--font-body);
  font-size: var(--step-0);
  cursor: pointer;
  text-align: left;
}

.node-count {
  color: var(--dim);
  font-family: var(--font-data);
  font-size: var(--step--1);
}

.reader-stage {
  position: relative;
  min-height: 100vh;
}

.spread {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-4);
  min-height: 100vh;
  padding: var(--space-5) var(--space-5) var(--space-7);
}

.continuous {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-5) var(--space-5) var(--space-7);
}

.reader-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-5);
  background: color-mix(in srgb, var(--ground) 78%, transparent);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  color: var(--text);
}

.ctrl {
  padding: var(--space-1) var(--space-3);
  border: 1px solid transparent;
  border-radius: var(--radius);
  background: transparent;
  color: var(--text);
  font-family: var(--font-body);
  font-size: var(--step--1);
  font-weight: 600;
  white-space: nowrap;
  cursor: pointer;
}

.ctrl:hover:not(:disabled) {
  border-color: var(--accent);
}

.ctrl:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.ctrl.active {
  background: var(--accent-dim);
  color: var(--accent);
}

.group {
  display: flex;
  gap: var(--space-1);
  padding: 2px;
  border-radius: var(--radius);
  background: rgb(0 0 0 / 0.25);
}

.page-label {
  font-family: var(--font-data);
  font-variant-numeric: tabular-nums;
  font-size: var(--step--1);
}

.attribution {
  position: fixed;
  right: var(--space-3);
  bottom: var(--space-2);
  z-index: 30;
  margin: 0;
  font-size: var(--step--1);
  color: var(--dim);
  opacity: 0.6;
  pointer-events: none;
}
</style>
