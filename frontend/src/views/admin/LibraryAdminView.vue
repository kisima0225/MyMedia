<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ApiError } from '@/api/client'
import {
  listLibraries, createLibrary, metadataProviders, setMetadataProviders, startScan,
  type CreateLibraryPayload,
} from '@/api/admin'
import { listShares, revokeShare } from '@/api/shares'
import type { Library, ShareLink } from '@/api/types'
import EmptyState from '@/components/common/EmptyState.vue'
import ErrorState from '@/components/common/ErrorState.vue'

/**
 * 两个域各自的徽标颜色，写死为字面量常量，不用 `var(--accent)`。
 *
 * 原因：这个页面是中性管理壳——它的路由 `admin-libraries`（router/index.ts）没有
 * `meta.domain`，AppShell 只按 `route.meta.domain` 给 `<body>` 打 `data-domain`
 * （见 AppShell.vue 的 watchEffect），所以本页 `<body>` 永远不带 `data-domain`，
 * `--accent` 在这里恒等于中性壳的 `--shell-focus`，VIDEO/IMAGE 两行会显示成同一个
 * 颜色，达不到"两种颜色分别取自两域"的效果。这两个值直接抄自 tokens.css 里
 * `[data-domain='video']`/`[data-domain='image']` 各自定义的 `--accent`
 * （分别是 `#ffb020`/`#c0483a`，已读源码核对），让徽标仍能体现两域的视觉差异。
 */
const DOMAIN_COLOR: Record<Library['domain'], string> = {
  VIDEO: '#ffb020',
  IMAGE: '#c0483a',
}

// ── 媒体库表格 ──
const status = ref<'loading' | 'ready' | 'error'>('loading')
const error = ref<unknown>(null)
const libraries = ref<Library[]>([])

/**
 * 每个库的刮削器列表，按 id 存。undefined = 还没查到（加载中），
 * 'load-error' = 查询这个库当前配置失败，'set-error' = 建库后紧接着设置刮削器
 * 那一步失败了（库本身已经真实建成，只是这一步没成功——见 submitCreate()
 * 的注释，这两种失败不能用同一个状态表示，否则用户分不清是"查不到"还是
 * "建库时没配上"），string[] = 查到的结果（空数组表示"不刮削"）。
 * GET /api/libraries 没有条目数字段，也没有能一次性带出刮削器配置的字段
 * （已读 LibraryDto.java 源码核对），所以逐库另发一次
 * GET /api/libraries/{id}/metadata-providers——库的数量通常很小，这个代价是合理的。
 */
const providersByLibrary = ref<Record<number, string[] | 'load-error' | 'set-error'>>({})

/**
 * 'set-error' 发生时，记下当时想设置的刮削器列表，供该行的"重试"按钮
 * （retryProviderSet）原样重新提交，不需要用户重新填一遍。
 */
const pendingProviders = ref<Record<number, string[]>>({})

type ScanState = 'idle' | 'starting' | 'started' | 'error'
const scanState = reactive<Record<number, ScanState>>({})
const scanErrorMessage = ref<Record<number, string>>({})

async function loadLibraries(): Promise<void> {
  status.value = 'loading'
  error.value = null
  try {
    libraries.value = await listLibraries()
    status.value = 'ready'
    void loadAllProviders()
  } catch (err) {
    error.value = err
    status.value = 'error'
  }
}

async function loadAllProviders(): Promise<void> {
  const targets = libraries.value
  const results = await Promise.allSettled(targets.map((lib) => metadataProviders(lib.id)))
  results.forEach((result, index) => {
    const lib = targets[index]
    providersByLibrary.value[lib.id] = result.status === 'fulfilled' ? result.value : 'load-error'
  })
}

function providerCellText(id: number): string {
  const entry = providersByLibrary.value[id]
  if (entry === undefined) return '加载中…'
  if (entry === 'load-error') return '加载失败'
  if (entry === 'set-error') return '设置失败'
  if (entry.length === 0) return '不刮削'
  return entry.join('、')
}

function providerCellDim(id: number): boolean {
  const entry = providersByLibrary.value[id]
  return entry === undefined || entry === 'load-error' || entry === 'set-error' || entry.length === 0
}

/** 'set-error' 那一行的"重试"：原样重新提交当时记下的刮削器列表。 */
async function retryProviderSet(id: number): Promise<void> {
  const providers = pendingProviders.value[id]
  if (!providers) return
  try {
    const saved = await setMetadataProviders(id, providers)
    providersByLibrary.value[id] = saved
    delete pendingProviders.value[id]
  } catch {
    providersByLibrary.value[id] = 'set-error'
    // 保留 pendingProviders[id]，允许再次点"重试"。
  }
}

const SCAN_LABEL: Record<ScanState, string> = {
  idle: '开始扫描',
  starting: '提交中…',
  started: '已开始扫描',
  error: '重试扫描',
}

function scanLabel(id: number): string {
  return SCAN_LABEL[scanState[id] ?? 'idle']
}

function scanDisabled(id: number): boolean {
  const s = scanState[id] ?? 'idle'
  return s === 'starting' || s === 'started'
}

/**
 * 点下去就禁用、改文案"已开始扫描"，不做进度轮询——后端没有扫描进度端点
 * （ScanController 只有 POST /scan，202 之后没有任何查询接口），编一个假进度条
 * 是在骗人。改用静态提示告诉管理员该怎么确认结果。
 */
async function scan(lib: Library): Promise<void> {
  if (scanDisabled(lib.id)) return
  scanState[lib.id] = 'starting'
  delete scanErrorMessage.value[lib.id]
  try {
    await startScan(lib.id)
    scanState[lib.id] = 'started'
  } catch (err) {
    scanState[lib.id] = 'error'
    scanErrorMessage.value[lib.id] = err instanceof ApiError ? err.message : '提交扫描请求失败，请重试。'
  }
}

// ── 新建媒体库 ──
const PROVIDER_OPTIONS = ['LocalNfo', 'Bangumi', 'TMDB', 'Filename'] as const

const form = reactive<{ name: string; domain: Library['domain']; rootPath: string; providers: string[] }>({
  name: '',
  domain: 'VIDEO',
  rootPath: '',
  providers: [],
})
const creating = ref(false)
const createError = ref<string | null>(null)

function toggleProvider(name: string, checked: boolean): void {
  if (checked) {
    if (!form.providers.includes(name)) form.providers.push(name)
  } else {
    form.providers = form.providers.filter((p) => p !== name)
  }
}

/**
 * 建库是两步：LibraryDto.CreateRequest 只有 name/domain/rootPath 三个字段，不含
 * 刮削器（已读 LibraryDto.java 源码核对）。刮削器多选框提交时先调 createLibrary
 * 拿新库 id，再紧接着调一次 setMetadataProviders(newId, providers)。
 *
 * 这两步刻意拆成两个独立的 try/catch，不共用一个：第一步一旦成功，库就是真实
 * 存在的资源（已经 push 进 libraries.value、显示在表格里）——如果这时候第二步
 * 失败，绝不能用同一句"创建媒体库失败，请重试"去提示用户，那会诱导用户重新
 * 提交同一份表单。全仓库没有任何 `DELETE /api/libraries/{id}` 端点（已用 grep
 * 核实），重新提交会建出一个界面和 API 都无法删除的重复库，是不可逆的脏状态。
 * 第二步失败时：明确提示库已建成、不要重复创建；表单不清空（用户至少能看到
 * 自己刚选的刮削器，方便核对/重试）；刮削器单元格显式标成 'set-error'（不是
 * 让它停在"加载中…"自己骗自己），并留一个"重试"按钮原样重新提交。
 */
async function submitCreate(): Promise<void> {
  if (creating.value) return
  const name = form.name.trim()
  const rootPath = form.rootPath.trim()
  if (!name || !rootPath) return

  creating.value = true
  createError.value = null

  let created: Library
  try {
    const payload: CreateLibraryPayload = { name, domain: form.domain, rootPath }
    created = await createLibrary(payload)
    libraries.value.push(created)
  } catch (err) {
    createError.value = err instanceof ApiError ? err.message : '创建媒体库失败，请重试。'
    creating.value = false
    return
  }

  if (form.providers.length > 0) {
    try {
      const saved = await setMetadataProviders(created.id, form.providers)
      providersByLibrary.value[created.id] = saved
    } catch (err) {
      const detail = err instanceof ApiError ? err.message : '请求失败'
      providersByLibrary.value[created.id] = 'set-error'
      pendingProviders.value[created.id] = [...form.providers]
      createError.value =
        `媒体库"${created.name}"已经创建成功，请勿重复提交。但刮削器设置失败：${detail}——` +
        '可以在下方表格该库这一行的"刮削器"列点"重试"。'
      creating.value = false
      return
    }
  } else {
    providersByLibrary.value[created.id] = []
  }

  form.name = ''
  form.rootPath = ''
  form.providers = []
  form.domain = 'VIDEO'
  creating.value = false
}

// ── 分享链接管理 ──
const sharesStatus = ref<'loading' | 'ready' | 'error'>('loading')
const sharesError = ref<unknown>(null)
const shares = ref<ShareLink[]>([])
const revokingIds = ref(new Set<number>())
const revokeErrorMessage = ref<Record<number, string>>({})

async function loadShares(): Promise<void> {
  sharesStatus.value = 'loading'
  sharesError.value = null
  try {
    shares.value = await listShares()
    sharesStatus.value = 'ready'
  } catch (err) {
    sharesError.value = err
    sharesStatus.value = 'error'
  }
}

/**
 * 一条分享链接的完整地址。
 *
 * 表格里只显示令牌前 8 位（完整令牌很长，铺开会把其他列挤没），但「事后找回
 * 一条已经创建好的分享链接」是这个面板的主要用途之一——只看前 8 位是拼不回
 * 地址的，所以每一行都配一个复制入口。路径形状与 ItemDetailView 创建成功后
 * 展示的那条一致：ShareView 挂在 /s/:token（router/index.ts）。
 */
function shareUrl(link: ShareLink): string {
  return `${location.origin}/s/${link.token}`
}

// 复制状态按行存：表格里同时有很多行，共用一个状态会让「已复制」显示在
// 用户没点过的那一行上。
const copyStatus = ref<Record<number, 'copied' | 'failed'>>({})
const COPY_LABEL: Record<'copied' | 'failed', string> = {
  copied: '已复制',
  failed: '复制失败',
}

function copyLabel(link: ShareLink): string {
  const state = copyStatus.value[link.id]
  return state ? COPY_LABEL[state] : '复制链接'
}

async function copyShareUrl(link: ShareLink): Promise<void> {
  // navigator.clipboard 在非安全上下文（http 访问的自托管部署）下是 undefined，
  // 不只是会 reject——所以这里要连「读属性就炸」一起兜住，失败时给一个可见的
  // 「复制失败」，让用户知道该手动选中地址栏里的链接。
  try {
    await navigator.clipboard.writeText(shareUrl(link))
    copyStatus.value[link.id] = 'copied'
  } catch {
    copyStatus.value[link.id] = 'failed'
  }
}

function shareStatusLabel(link: ShareLink): string {
  if (link.revokedAt) return '已撤销'
  if (link.expiresAt && new Date(link.expiresAt).getTime() <= Date.now()) return '已过期'
  return '有效'
}

function formatDateTime(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString() : '—'
}

async function revoke(link: ShareLink): Promise<void> {
  if (revokingIds.value.has(link.id) || link.revokedAt) return
  revokingIds.value.add(link.id)
  delete revokeErrorMessage.value[link.id]
  try {
    await revokeShare(link.id)
    // DELETE /api/shares/{id} 没有响应体，直接把本地这一条标记为已撤销，
    // 不为了这一条改动重新整表拉取。
    const target = shares.value.find((s) => s.id === link.id)
    if (target) target.revokedAt = new Date().toISOString()
  } catch (err) {
    revokeErrorMessage.value[link.id] = err instanceof ApiError ? err.message : '撤销失败，请重试。'
  } finally {
    revokingIds.value.delete(link.id)
  }
}

onMounted(() => {
  void loadLibraries()
  void loadShares()
})
</script>

<template>
  <div class="admin-view">
    <h1 class="page-title">媒体库管理</h1>

    <section class="panel">
      <h2 class="panel-title">媒体库</h2>

      <div v-if="status === 'loading'" class="skeleton-rows">
        <div v-for="n in 3" :key="n" class="skeleton-row" />
      </div>

      <ErrorState v-else-if="status === 'error'" :error="error" :onRetry="loadLibraries" />

      <template v-else>
        <EmptyState
          v-if="libraries.length === 0"
          title="还没有任何媒体库"
          hint="在下方表单新建一个，指向服务器上的一个真实目录。"
        />

        <div v-else class="table-scroll">
          <table class="lib-table">
            <thead>
              <tr>
                <th>名称</th>
                <th>域</th>
                <th>根路径</th>
                <th>刮削器</th>
                <th>启用状态</th>
                <th>扫描</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="lib in libraries" :key="lib.id">
                <td>{{ lib.name }}</td>
                <td>
                  <span class="domain-badge" :style="{ '--badge-color': DOMAIN_COLOR[lib.domain] }">
                    {{ lib.domain }}
                  </span>
                </td>
                <td class="mono">{{ lib.rootPath }}</td>
                <td :class="{ dim: providerCellDim(lib.id) }">
                  {{ providerCellText(lib.id) }}
                  <button
                    v-if="providersByLibrary[lib.id] === 'set-error'"
                    type="button"
                    class="retry-inline"
                    @click="retryProviderSet(lib.id)"
                  >
                    重试
                  </button>
                </td>
                <td>{{ lib.enabled ? '启用' : '停用' }}</td>
                <td>
                  <button
                    type="button"
                    class="scan-btn"
                    :class="{ error: scanState[lib.id] === 'error' }"
                    :disabled="scanDisabled(lib.id)"
                    @click="scan(lib)"
                  >
                    {{ scanLabel(lib.id) }}
                  </button>
                  <p v-if="scanState[lib.id] === 'started'" class="scan-hint">
                    扫描在后台进行，完成后刷新页面即可看到新内容。
                  </p>
                  <p v-else-if="scanState[lib.id] === 'error'" class="scan-hint error-text">
                    {{ scanErrorMessage[lib.id] }}
                  </p>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>

      <form class="create-form" @submit.prevent="submitCreate">
        <h3 class="form-title">新建媒体库</h3>

        <label class="field">
          <span class="field-label">名称</span>
          <input v-model="form.name" type="text" required maxlength="128" placeholder="例如：家庭影院" />
        </label>

        <label class="field">
          <span class="field-label">域</span>
          <select v-model="form.domain">
            <option value="VIDEO">VIDEO（视频）</option>
            <option value="IMAGE">IMAGE（图片）</option>
          </select>
          <p class="field-hint">创建后不可更改——后端 libraries.domain 是建库后的不可变字段。</p>
        </label>

        <label class="field">
          <span class="field-label">根路径</span>
          <input v-model="form.rootPath" type="text" required placeholder="服务器上的绝对路径，例如 /data/anime" />
        </label>

        <fieldset class="field">
          <legend class="field-label">刮削器</legend>
          <div class="checkbox-row">
            <label v-for="name in PROVIDER_OPTIONS" :key="name" class="checkbox">
              <input
                type="checkbox"
                :checked="form.providers.includes(name)"
                @change="toggleProvider(name, ($event.target as HTMLInputElement).checked)"
              />
              <span>{{ name }}</span>
            </label>
          </div>
          <p class="field-hint">留空表示这个库不刮削，其中的条目不会显示任何刮削状态。</p>
        </fieldset>

        <p v-if="createError" class="error-text">{{ createError }}</p>

        <button type="submit" class="submit-btn" :disabled="creating">
          {{ creating ? '创建中…' : '创建媒体库' }}
        </button>
      </form>
    </section>

    <section class="panel">
      <h2 class="panel-title">分享链接管理</h2>

      <div v-if="sharesStatus === 'loading'" class="skeleton-rows">
        <div v-for="n in 3" :key="n" class="skeleton-row" />
      </div>

      <ErrorState v-else-if="sharesStatus === 'error'" :error="sharesError" :onRetry="loadShares" />

      <EmptyState v-else-if="shares.length === 0" title="还没有创建任何分享链接" />

      <div v-else class="table-scroll">
        <table class="lib-table">
          <thead>
            <tr>
              <th>令牌</th>
              <th>目标</th>
              <th>创建时间</th>
              <th>过期时间</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="link in shares" :key="link.id">
              <td class="mono">
                <span class="token-text" :title="shareUrl(link)">{{ link.token.slice(0, 8) }}</span>
                <button type="button" class="copy-inline" @click="copyShareUrl(link)">
                  {{ copyLabel(link) }}
                </button>
              </td>
              <td>
                <RouterLink
                  v-if="link.domain === 'VIDEO'"
                  :to="{ name: 'video-item', params: { id: link.targetId } }"
                >
                  VIDEO #{{ link.targetId }}
                </RouterLink>
                <RouterLink v-else :to="{ name: 'image-node', params: { id: link.targetId } }">
                  IMAGE #{{ link.targetId }}
                </RouterLink>
              </td>
              <td class="mono">{{ formatDateTime(link.createdAt) }}</td>
              <td class="mono">{{ formatDateTime(link.expiresAt) }}</td>
              <td>{{ shareStatusLabel(link) }}</td>
              <td>
                <button
                  v-if="!link.revokedAt"
                  type="button"
                  class="scan-btn"
                  :disabled="revokingIds.has(link.id)"
                  @click="revoke(link)"
                >
                  {{ revokingIds.has(link.id) ? '撤销中…' : '撤销' }}
                </button>
                <span v-else class="dim">—</span>
                <p v-if="revokeErrorMessage[link.id]" class="scan-hint error-text">
                  {{ revokeErrorMessage[link.id] }}
                </p>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>

<style scoped>
.admin-view {
  display: flex;
  flex-direction: column;
  gap: var(--space-6);
}

.page-title {
  font-family: var(--display);
  font-weight: 600;
  font-size: var(--step-2);
  color: var(--text);
}

.panel {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
  padding: var(--space-5);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--raised);
}

.panel-title {
  font-family: var(--display);
  font-weight: 600;
  font-size: var(--step-1);
  color: var(--text);
}

.table-scroll {
  overflow-x: auto;
}

.lib-table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--step--1);
}

.lib-table th {
  padding: var(--space-2) var(--space-3);
  border-bottom: 1px solid var(--line);
  color: var(--dim);
  text-align: left;
  font-weight: 600;
  white-space: nowrap;
}

.lib-table td {
  padding: var(--space-3);
  border-bottom: 1px solid var(--line);
  color: var(--text);
  vertical-align: top;
}

.mono {
  font-family: var(--font-data);
}

.dim {
  color: var(--dim);
}

.domain-badge {
  display: inline-block;
  padding: 2px var(--space-2);
  border: 1px solid var(--badge-color);
  border-radius: var(--radius);
  color: var(--badge-color);
  font-family: var(--font-data);
  font-size: var(--step--1);
  font-weight: 700;
}

.scan-btn {
  padding: var(--space-1) var(--space-3);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--ground);
  color: var(--text);
  font-family: var(--font-body);
  font-size: var(--step--1);
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
  transition: border-color var(--dur-fast) var(--ease);
}

.scan-btn:hover:not(:disabled) {
  border-color: var(--accent);
}

.scan-btn:disabled {
  opacity: 0.6;
  cursor: default;
}

.scan-btn.error {
  border-color: #b5533f;
  color: #d98a78;
}

.scan-hint {
  margin-top: var(--space-1);
  max-width: 32ch;
  color: var(--dim);
  font-size: var(--step--1);
}

.error-text {
  color: #d98a78;
}

.token-text {
  white-space: nowrap;
}

.copy-inline {
  margin-left: var(--space-2);
  padding: 1px var(--space-2);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: transparent;
  color: var(--dim);
  font-family: var(--font-body);
  font-size: var(--step--1);
  font-weight: 600;
  white-space: nowrap;
  cursor: pointer;
  transition: border-color var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}

.copy-inline:hover {
  border-color: var(--accent);
  color: var(--text);
}

.retry-inline {
  margin-left: var(--space-2);
  padding: 1px var(--space-2);
  border: 1px solid #b5533f;
  border-radius: var(--radius);
  background: transparent;
  color: #d98a78;
  font-family: var(--font-body);
  font-size: var(--step--1);
  font-weight: 600;
  cursor: pointer;
}

.skeleton-rows {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.skeleton-row {
  height: 2.5em;
  border-radius: var(--radius);
  background: var(--ground);
  animation: skeleton-breathe 1.4s ease-in-out infinite;
}

@keyframes skeleton-breathe {
  0%,
  100% {
    opacity: 0.55;
  }
  50% {
    opacity: 1;
  }
}

.create-form {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  max-width: 480px;
  padding-top: var(--space-4);
  border-top: 1px solid var(--line);
}

.form-title {
  font-family: var(--display);
  font-weight: 600;
  font-size: var(--step-0);
  color: var(--text);
}

.field {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  border: none;
  padding: 0;
  margin: 0;
}

.field-label {
  font-size: var(--step--1);
  font-weight: 600;
  color: var(--dim);
}

.field input[type='text'],
.field select {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--ground);
  color: var(--text);
  font-family: var(--font-body);
  font-size: var(--step-0);
}

.field input:focus-visible,
.field select:focus-visible {
  border-color: var(--accent);
}

.field-hint {
  color: var(--dim);
  font-size: var(--step--1);
}

.checkbox-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
}

.checkbox {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  font-size: var(--step-0);
  color: var(--text);
  cursor: pointer;
}

.submit-btn {
  align-self: flex-start;
  padding: var(--space-2) var(--space-4);
  border: none;
  border-radius: var(--radius);
  background: var(--accent);
  color: var(--shell-ground);
  font-family: var(--font-body);
  font-size: var(--step-0);
  font-weight: 700;
  cursor: pointer;
}

.submit-btn:disabled {
  opacity: 0.6;
  cursor: default;
}
</style>
