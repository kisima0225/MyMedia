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
 * 'error' = 查询失败，string[] = 查到的结果（空数组表示"不刮削"）。
 * GET /api/libraries 没有条目数字段，也没有能一次性带出刮削器配置的字段
 * （已读 LibraryDto.java 源码核对），所以逐库另发一次
 * GET /api/libraries/{id}/metadata-providers——库的数量通常很小，这个代价是合理的。
 */
const providersByLibrary = ref<Record<number, string[] | 'error'>>({})

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
    providersByLibrary.value[lib.id] = result.status === 'fulfilled' ? result.value : 'error'
  })
}

function providerCellText(id: number): string {
  const entry = providersByLibrary.value[id]
  if (entry === undefined) return '加载中…'
  if (entry === 'error') return '加载失败'
  if (entry.length === 0) return '不刮削'
  return entry.join('、')
}

function providerCellDim(id: number): boolean {
  const entry = providersByLibrary.value[id]
  return entry === undefined || entry === 'error' || entry.length === 0
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
 */
async function submitCreate(): Promise<void> {
  if (creating.value) return
  const name = form.name.trim()
  const rootPath = form.rootPath.trim()
  if (!name || !rootPath) return

  creating.value = true
  createError.value = null
  try {
    const payload: CreateLibraryPayload = { name, domain: form.domain, rootPath }
    const created = await createLibrary(payload)
    libraries.value.push(created)

    if (form.providers.length > 0) {
      const saved = await setMetadataProviders(created.id, form.providers)
      providersByLibrary.value[created.id] = saved
    } else {
      providersByLibrary.value[created.id] = []
    }

    form.name = ''
    form.rootPath = ''
    form.providers = []
    form.domain = 'VIDEO'
  } catch (err) {
    createError.value = err instanceof ApiError ? err.message : '创建媒体库失败，请重试。'
  } finally {
    creating.value = false
  }
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
                <td :class="{ dim: providerCellDim(lib.id) }">{{ providerCellText(lib.id) }}</td>
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
              <td class="mono">{{ link.token.slice(0, 8) }}</td>
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
