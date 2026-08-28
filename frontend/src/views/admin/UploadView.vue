<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ApiError } from '@/api/client'
import { listLibraries, createUploadSession, getUploadSession, putUploadChunk } from '@/api/admin'
import type { Library, UploadSession } from '@/api/types'
import { sliceFile, missingChunks, uploadProgress, type ChunkRange } from '@/lib/chunker'
import { sampledHash, hasSubtleCrypto } from '@/lib/sampledHash'
import ErrorState from '@/components/common/ErrorState.vue'

// ── 媒体库下拉 ──
const librariesStatus = ref<'loading' | 'ready' | 'error'>('loading')
const librariesError = ref<unknown>(null)
const libraries = ref<Library[]>([])

async function loadLibraries(): Promise<void> {
  librariesStatus.value = 'loading'
  librariesError.value = null
  try {
    libraries.value = await listLibraries()
    librariesStatus.value = 'ready'
  } catch (err) {
    librariesError.value = err
    librariesStatus.value = 'error'
  }
}

// crypto.subtle 只在安全上下文（HTTPS 或 localhost）里可用——先检测，不存在时
// 禁用整个上传入口并说明原因，而不是让页面在一个 undefined.digest 上崩掉。
const cryptoAvailable = hasSubtleCrypto()

const selectedLibraryId = ref<number | null>(null)

function onLibraryChange(event: Event): void {
  const value = (event.target as HTMLSelectElement).value
  selectedLibraryId.value = value ? Number(value) : null
}

// ── 断点续传：sessionId 存 localStorage，回来时先 GET 一次会话拿 received ──
const STORAGE_KEY = 'mymedia.upload.pendingSession'

interface StoredUploadSession {
  sessionId: number
  filename: string
  totalSize: number
  contentHash: string
  targetLibraryId: number
}

const resumeCandidate = ref<StoredUploadSession | null>(null)
const resumeSessionInfo = ref<UploadSession | null>(null)

function readStoredSession(): StoredUploadSession | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as Partial<StoredUploadSession>
    if (typeof parsed.sessionId !== 'number') return null
    return parsed as StoredUploadSession
  } catch {
    return null
  }
}

function persistStoredSession(stored: StoredUploadSession): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(stored))
  } catch {
    // localStorage 不可用（隐私模式等）时静默降级——只是失去断点续传能力，不影响本次上传
  }
}

function clearStoredSession(): void {
  try {
    localStorage.removeItem(STORAGE_KEY)
  } catch {
    // 同上
  }
  resumeCandidate.value = null
  resumeSessionInfo.value = null
}

async function discardResume(): Promise<void> {
  clearStoredSession()
}

onMounted(async () => {
  void loadLibraries()

  const stored = readStoredSession()
  if (!stored) return

  try {
    const info = await getUploadSession(stored.sessionId)
    if (info.status === 'RECEIVING') {
      resumeCandidate.value = stored
      resumeSessionInfo.value = info
      selectedLibraryId.value = stored.targetLibraryId
    } else if (info.status === 'ASSEMBLING') {
      // 分片已经全部传完、服务端正在合并——这个阶段不需要文件本身，不用等用户
      // 重新选择文件才能继续。直接进度轮询区，而不是显示"请重新选择同一个文件"
      // 的续传横幅（那句提示在这个阶段不适用，会误导用户以为还需要再选一次文件）。
      // pollUntilAssembled 自己有有界重试与错误处理，这里不需要再包一层 try/catch。
      selectedLibraryId.value = stored.targetLibraryId
      session.value = info
      uploadedIndices.value = new Set(info.receivedChunks)
      phase.value = 'assembling'
      await pollUntilAssembled(stored.sessionId)
    } else {
      // 已经完成或失败：本地记录过期了，不再提示续传
      clearStoredSession()
    }
  } catch {
    // 查询会话本身失败（比如换了一台服务器、会话已经不存在），清掉本地的脏记录
    clearStoredSession()
  }
})

// ── 文件选择 ──
const file = ref<File | null>(null)

function onFileChange(event: Event): void {
  const target = event.target as HTMLInputElement
  file.value = target.files?.[0] ?? null
  if (!busy.value) {
    // 选新文件时把上一轮的终态（完成/失败）清掉，回到可以重新提交的状态
    phase.value = 'idle'
    errorMessage.value = ''
  }
}

// ── 上传流程状态机 ──
type Phase = 'idle' | 'hashing' | 'creating' | 'uploading' | 'assembling' | 'done-instant' | 'done' | 'error'
const phase = ref<Phase>('idle')
const errorMessage = ref('')
const session = ref<UploadSession | null>(null)
const uploadedIndices = ref<Set<number>>(new Set())
const bytesUploadedThisRun = ref(0)
const uploadStartedAt = ref<number | null>(null)
const speedBps = ref(0)

const busy = computed(
  () => phase.value === 'hashing' || phase.value === 'creating'
    || phase.value === 'uploading' || phase.value === 'assembling',
)
const canSubmit = computed(() => cryptoAvailable && file.value !== null
  && selectedLibraryId.value !== null && !busy.value)
const submitLabel = computed(() => (busy.value ? '上传中…' : '开始上传'))

const progressRatio = computed(() => (
  session.value ? uploadProgress(session.value.totalChunks, Array.from(uploadedIndices.value)) : 0
))

function formatSpeed(bytesPerSecond: number): string {
  if (!(bytesPerSecond > 0)) return '—'
  if (bytesPerSecond >= 1024 * 1024) return `${(bytesPerSecond / (1024 * 1024)).toFixed(1)} MB/s`
  if (bytesPerSecond >= 1024) return `${(bytesPerSecond / 1024).toFixed(1)} KB/s`
  return `${bytesPerSecond.toFixed(0)} B/s`
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/** 更新速度：这次运行里已传字节数 / 已耗时。简单平均，足够给用户一个数量级参考。 */
function updateSpeed(): void {
  if (uploadStartedAt.value === null) return
  const elapsedSeconds = (Date.now() - uploadStartedAt.value) / 1000
  speedBps.value = elapsedSeconds > 0 ? bytesUploadedThisRun.value / elapsedSeconds : 0
}

/**
 * 并发 3 片，每片失败重试 3 次（指数退避 1s / 2s / 4s）。不开更大的并发——
 * 单实例服务端，磁盘 I/O 才是瓶颈，多开只会增加锁等待。
 */
const CONCURRENCY = 3
const RETRY_DELAYS_MS = [1000, 2000, 4000]

async function uploadWithConcurrency(
  targetFile: File,
  sessionId: number,
  parts: ChunkRange[],
): Promise<void> {
  let cursor = 0
  let failure: unknown = null

  async function worker(): Promise<void> {
    while (cursor < parts.length && !failure) {
      const range = parts[cursor]
      cursor += 1
      const blob = targetFile.slice(range.start, range.end)

      let attempt = 0
      for (;;) {
        try {
          await putUploadChunk(sessionId, range.index, blob)
          break
        } catch (err) {
          if (attempt >= RETRY_DELAYS_MS.length) {
            failure = err
            return
          }
          await sleep(RETRY_DELAYS_MS[attempt])
          attempt += 1
        }
      }

      uploadedIndices.value.add(range.index)
      bytesUploadedThisRun.value += range.end - range.start
      updateSpeed()
    }
  }

  const workerCount = Math.min(CONCURRENCY, parts.length)
  await Promise.all(Array.from({ length: workerCount }, () => worker()))
  if (failure) throw failure
}

/**
 * 分片到齐后后端异步合并（走任务队列），轮询直到 COMPLETED / FAILED。
 *
 * 轮询本身也可能失败（网络抖动、后端短暂重启）——给 `getUploadSession` 一次
 * 有界重试，和分片上传同一套纪律（3 次、退避 1s/2s/4s），而不是让一次瞬时的
 * 网络错误就变成一个未捕获的 promise rejection，把 phase 永远卡在 'assembling'
 * 且界面上什么提示都没有。重试耗尽后**在这个函数内部**直接把状态落成用户能
 * 看见的错误，而不是把异常抛给调用方——调用方（`runUpload`/`onMounted` 的续传
 * 分支）因此不需要去猜"这个异常到底是合并阶段失败、还是创建会话/续传前置步骤
 * 失败"：这个函数自己对自己的失败负责，调用方看到的永远是"这个函数正常返回"。
 */
async function pollUntilAssembled(sessionId: number): Promise<void> {
  let consecutiveFailures = 0
  for (;;) {
    let current: UploadSession
    try {
      current = await getUploadSession(sessionId)
      consecutiveFailures = 0
    } catch (err) {
      if (consecutiveFailures >= RETRY_DELAYS_MS.length) {
        phase.value = 'error'
        errorMessage.value = err instanceof ApiError
          ? err.message
          : '查询合并进度失败，请刷新页面重试（分片已经全部传完，服务端很可能仍在正常合并）。'
        return
      }
      await sleep(RETRY_DELAYS_MS[consecutiveFailures])
      consecutiveFailures += 1
      continue
    }

    session.value = current
    if (current.status === 'COMPLETED') {
      phase.value = 'done'
      clearStoredSession()
      return
    }
    if (current.status === 'FAILED') {
      phase.value = 'error'
      errorMessage.value = current.lastError ?? '合并失败，原因未知。'
      clearStoredSession()
      return
    }
    await sleep(1000)
  }
}

async function runUpload(targetFile: File, uploadSession: UploadSession): Promise<void> {
  phase.value = 'uploading'
  session.value = uploadSession
  uploadedIndices.value = new Set(uploadSession.receivedChunks)
  bytesUploadedThisRun.value = 0
  uploadStartedAt.value = Date.now()

  const parts = sliceFile(targetFile, uploadSession.chunkSize)
  const missing = new Set(missingChunks(uploadSession.totalChunks, uploadSession.receivedChunks))
  const missingParts = parts.filter((p) => missing.has(p.index))

  try {
    await uploadWithConcurrency(targetFile, uploadSession.id, missingParts)
  } catch (err) {
    phase.value = 'error'
    errorMessage.value = err instanceof ApiError
      ? err.message
      : '分片上传失败，请重试（已经传成功的分片下次会自动跳过）。'
    return
  }

  phase.value = 'assembling'
  await pollUntilAssembled(uploadSession.id)
}

async function startUpload(): Promise<void> {
  if (!canSubmit.value || !file.value || selectedLibraryId.value === null) return
  const targetFile = file.value
  errorMessage.value = ''

  // 续传：本地记着一个未完成的会话，且重新选的文件名字、大小都对得上
  if (resumeSessionInfo.value && resumeCandidate.value
      && resumeCandidate.value.filename === targetFile.name
      && resumeCandidate.value.totalSize === targetFile.size) {
    // 与下面"全新上传"分支同一套 try/catch 纪律：任何一步（算哈希、续传上传本身）
    // 出错都要落地成用户能看见的错误状态，而不是变成一个未捕获的 promise
    // rejection、把 phase 永远卡在某个中间态（review 意见——续传路径此前完全
    // 没有包 try/catch）。
    try {
      phase.value = 'hashing'
      const hash = await sampledHash(targetFile)
      if (hash !== resumeCandidate.value.contentHash) {
        phase.value = 'idle'
        errorMessage.value = '这个文件的内容与上次记录的不同，无法续传该会话。'
          + '请点击上方"放弃并重新开始"，或重新选择原来的文件。'
        return
      }
      await runUpload(targetFile, resumeSessionInfo.value)
    } catch (err) {
      phase.value = 'error'
      errorMessage.value = err instanceof ApiError ? err.message : '续传失败，请重试。'
    }
    return
  }

  // 全新上传
  try {
    phase.value = 'hashing'
    const contentHash = await sampledHash(targetFile)

    phase.value = 'creating'
    const created = await createUploadSession({
      filename: targetFile.name,
      totalSize: targetFile.size,
      contentHash,
      targetLibraryId: selectedLibraryId.value,
    })

    if (created.instant || created.status === 'COMPLETED') {
      session.value = created
      phase.value = 'done-instant'
      clearStoredSession()
      return
    }

    persistStoredSession({
      sessionId: created.id,
      filename: targetFile.name,
      totalSize: targetFile.size,
      contentHash,
      targetLibraryId: selectedLibraryId.value,
    })
    await runUpload(targetFile, created)
  } catch (err) {
    phase.value = 'error'
    errorMessage.value = err instanceof ApiError ? err.message : '创建上传会话失败，请重试。'
  }
}
</script>

<template>
  <div class="admin-view">
    <h1 class="page-title">上传</h1>

    <section v-if="resumeSessionInfo && resumeCandidate" class="panel resume-banner">
      <p class="resume-text">
        发现上次未完成的上传：<strong>{{ resumeCandidate.filename }}</strong>
        （已传 {{ resumeSessionInfo.receivedChunks.length }} / {{ resumeSessionInfo.totalChunks }} 片，
        约 {{ Math.round(uploadProgress(resumeSessionInfo.totalChunks, resumeSessionInfo.receivedChunks) * 100) }}%）。
        请重新选择<strong>同一个文件</strong>以继续上传。
      </p>
      <button type="button" class="discard-btn" @click="discardResume">放弃并重新开始</button>
    </section>

    <section class="panel">
      <div v-if="librariesStatus === 'loading'" class="skeleton-rows">
        <div v-for="n in 2" :key="n" class="skeleton-row" />
      </div>

      <ErrorState v-else-if="librariesStatus === 'error'" :error="librariesError" :onRetry="loadLibraries" />

      <template v-else>
        <p v-if="!cryptoAvailable" class="error-text">
          当前环境不支持 crypto.subtle（计算内容哈希需要它，只在 HTTPS 或 localhost 下可用），无法在此上传。
        </p>

        <form class="upload-form" @submit.prevent="startUpload">
          <label class="field">
            <span class="field-label">目标媒体库</span>
            <select :value="selectedLibraryId ?? ''" :disabled="busy" @change="onLibraryChange">
              <option value="" disabled>选择一个媒体库</option>
              <option v-for="lib in libraries" :key="lib.id" :value="lib.id">
                {{ lib.name }}（{{ lib.domain }}）
              </option>
            </select>
          </label>

          <label class="field">
            <span class="field-label">文件</span>
            <input type="file" :disabled="busy" @change="onFileChange" />
          </label>

          <p v-if="errorMessage" class="error-text">{{ errorMessage }}</p>

          <button type="submit" class="submit-btn" :disabled="!canSubmit">{{ submitLabel }}</button>
        </form>

        <div v-if="phase !== 'idle'" class="progress-block">
          <p v-if="phase === 'hashing'" class="status-line">计算内容哈希中…</p>
          <p v-else-if="phase === 'creating'" class="status-line">创建上传会话中…</p>
          <p v-else-if="phase === 'done-instant'" class="status-line success">已存在相同文件，秒传完成。</p>

          <template v-else-if="phase === 'uploading' || phase === 'assembling'">
            <div class="progress-bar">
              <div class="progress-fill" :style="{ width: `${progressRatio * 100}%` }" />
            </div>
            <p class="progress-stats mono">
              {{ (progressRatio * 100).toFixed(1) }}% ·
              {{ uploadedIndices.size }} / {{ session?.totalChunks ?? 0 }} 片 ·
              {{ formatSpeed(speedBps) }}
            </p>
            <p v-if="phase === 'assembling'" class="status-line">分片已全部到齐，服务端合并中…</p>
          </template>

          <p v-else-if="phase === 'done'" class="status-line success">上传完成。</p>
          <p v-else-if="phase === 'error' && errorMessage" class="error-text">{{ errorMessage }}</p>
        </div>
      </template>
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
  gap: var(--space-4);
  padding: var(--space-5);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--raised);
}

.resume-banner {
  border-color: var(--accent);
  background: var(--ground);
}

.resume-text {
  color: var(--text);
  font-size: var(--step-0);
  line-height: 1.6;
}

.discard-btn {
  align-self: flex-start;
  padding: var(--space-1) var(--space-3);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: transparent;
  color: var(--dim);
  font-family: var(--font-body);
  font-size: var(--step--1);
  font-weight: 600;
  cursor: pointer;
}

.discard-btn:hover {
  border-color: var(--accent);
  color: var(--text);
}

.upload-form {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  max-width: 480px;
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

.field select,
.field input[type='file'] {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--ground);
  color: var(--text);
  font-family: var(--font-body);
  font-size: var(--step-0);
}

.field select:focus-visible {
  border-color: var(--accent);
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

.error-text {
  color: #d98a78;
}

.progress-block {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  max-width: 480px;
  padding-top: var(--space-3);
  border-top: 1px solid var(--line);
}

.status-line {
  color: var(--dim);
  font-size: var(--step-0);
}

.status-line.success {
  color: var(--text);
  font-weight: 600;
}

.progress-bar {
  height: 8px;
  border-radius: var(--radius);
  background: var(--ground);
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: var(--accent);
  transition: width var(--dur-base) var(--ease);
}

.progress-stats.mono {
  font-family: var(--font-data);
  font-size: var(--step--1);
  color: var(--dim);
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
</style>
