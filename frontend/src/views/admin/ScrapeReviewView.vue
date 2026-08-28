<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ApiError } from '@/api/client'
import { scrapeQueue, confirmScrapeCandidate, ignoreScrapeCandidates } from '@/api/admin'
import type { ScrapeCandidate, ScrapeQueueEntry } from '@/api/types'
import EmptyState from '@/components/common/EmptyState.vue'
import ErrorState from '@/components/common/ErrorState.vue'
import Cover from '@/components/common/Cover.vue'

/**
 * 两个域各自的徽标颜色，字面量常量而不是 var(--accent)——这个页面（路由
 * admin-scrape）没有 meta.domain，<body> 不会被打 data-domain，--accent 恒等于
 * 中性壳色，VIDEO/IMAGE 两个徽标会显示成同一个颜色。值抄自 tokens.css 的
 * [data-domain='video']/[data-domain='image'] 各自的 --accent（与
 * LibraryAdminView.vue 同一处理方式）。
 */
const DOMAIN_COLOR: Record<ScrapeQueueEntry['domain'], string> = {
  VIDEO: '#ffb020',
  IMAGE: '#c0483a',
}

const status = ref<'loading' | 'ready' | 'error'>('loading')
const error = ref<unknown>(null)
const queue = ref<ScrapeQueueEntry[]>([])

/** 当前正在处理（确认/忽略请求进行中）的目标 key，同一时间只允许一个动作在跑。 */
const busyKey = ref<string | null>(null)
const entryError = reactive<Record<string, string>>({})

function keyOf(entry: ScrapeQueueEntry): string {
  return `${entry.domain}:${entry.targetId}`
}

async function loadQueue(): Promise<void> {
  status.value = 'loading'
  error.value = null
  try {
    queue.value = await scrapeQueue()
    status.value = 'ready'
  } catch (err) {
    error.value = err
    status.value = 'error'
  }
}

/**
 * 确认后端会清空这个目标的全部候选（ScrapeCandidateService.confirm 里
 * store.deleteAll），所以成功后整条队列项从列表里消失，不只是移除被选中的那个候选。
 */
async function confirm(entry: ScrapeQueueEntry, candidate: ScrapeCandidate): Promise<void> {
  const key = keyOf(entry)
  if (busyKey.value) return
  busyKey.value = key
  delete entryError[key]
  try {
    await confirmScrapeCandidate(candidate.id)
    queue.value = queue.value.filter((e) => keyOf(e) !== key)
  } catch (err) {
    entryError[key] = err instanceof ApiError ? err.message : '确认失败，请重试。'
  } finally {
    busyKey.value = null
  }
}

/** 「都不是」作用于整个目标（POST /ignore 只接受 domain+targetId，不是某一个候选 id）。 */
async function ignoreEntry(entry: ScrapeQueueEntry): Promise<void> {
  const key = keyOf(entry)
  if (busyKey.value) return
  busyKey.value = key
  delete entryError[key]
  try {
    await ignoreScrapeCandidates(entry.domain, entry.targetId)
    queue.value = queue.value.filter((e) => keyOf(e) !== key)
  } catch (err) {
    entryError[key] = err instanceof ApiError ? err.message : '标记失败，请重试。'
  } finally {
    busyKey.value = null
  }
}

onMounted(() => {
  void loadQueue()
})
</script>

<template>
  <div class="admin-view">
    <h1 class="page-title">刮削审核</h1>
    <p class="legend">
      相似度 <span class="mono">≥ 0.80</span> 会自动应用，不会出现在这里；
      <span class="mono">0.40–0.80</span> 的候选进入这个队列，等待人工确认。
    </p>

    <div v-if="status === 'loading'" class="skeleton-rows">
      <div v-for="n in 3" :key="n" class="skeleton-row" />
    </div>

    <ErrorState v-else-if="status === 'error'" :error="error" :onRetry="loadQueue" />

    <EmptyState
      v-else-if="queue.length === 0"
      title="没有待确认的刮削结果。"
      hint="刮削是可选增强——找不到匹配是正常的，条目会安静地用文件名元数据。"
    />

    <ul v-else class="queue-list">
      <li v-for="entry in queue" :key="keyOf(entry)" class="queue-entry">
        <div class="target">
          <Cover :assetId="entry.coverAssetId" ratio="2/3" :alt="entry.title" />
          <div class="target-info">
            <span class="domain-badge" :style="{ '--badge-color': DOMAIN_COLOR[entry.domain] }">
              {{ entry.domain }}
            </span>
            <p class="target-title">{{ entry.title }}</p>
          </div>
        </div>

        <div class="candidates">
          <div v-for="candidate in entry.candidates" :key="candidate.id" class="candidate-row">
            <div class="candidate-info">
              <p class="candidate-title">
                {{ candidate.title }}
                <span v-if="candidate.year" class="candidate-year">（{{ candidate.year }}）</span>
              </p>
              <p class="candidate-meta">
                来源 {{ candidate.provider }} · 相似度
                <span class="mono">{{ candidate.score.toFixed(2) }}</span>
              </p>
            </div>
            <button
              type="button"
              class="confirm-btn"
              :disabled="busyKey === keyOf(entry)"
              @click="confirm(entry, candidate)"
            >
              确认这一条
            </button>
          </div>

          <div class="entry-actions">
            <button
              type="button"
              class="ignore-btn"
              :disabled="busyKey === keyOf(entry)"
              @click="ignoreEntry(entry)"
            >
              {{ busyKey === keyOf(entry) ? '处理中…' : '都不是' }}
            </button>
            <p v-if="entryError[keyOf(entry)]" class="error-text">{{ entryError[keyOf(entry)] }}</p>
          </div>
        </div>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.admin-view {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

.page-title {
  font-family: var(--display);
  font-weight: 600;
  font-size: var(--step-2);
  color: var(--text);
}

.legend {
  color: var(--dim);
  font-size: var(--step-0);
  max-width: 60ch;
}

.legend .mono {
  font-family: var(--font-data);
  color: var(--text);
}

.queue-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

.queue-entry {
  display: grid;
  grid-template-columns: 120px 1fr;
  gap: var(--space-5);
  padding: var(--space-5);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--raised);
}

.target {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.target-info {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.domain-badge {
  align-self: flex-start;
  padding: 2px var(--space-2);
  border: 1px solid var(--badge-color);
  border-radius: var(--radius);
  color: var(--badge-color);
  font-family: var(--font-data);
  font-size: var(--step--1);
  font-weight: 700;
}

.target-title {
  color: var(--text);
  font-size: var(--step-0);
  font-weight: 600;
  word-break: break-word;
}

.candidates {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.candidate-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  padding: var(--space-3);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--ground);
}

.candidate-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.candidate-title {
  color: var(--text);
  font-size: var(--step-0);
}

.candidate-year {
  color: var(--dim);
}

.candidate-meta {
  color: var(--dim);
  font-size: var(--step--1);
}

.candidate-meta .mono {
  font-family: var(--font-data);
  color: var(--text);
}

.confirm-btn {
  flex-shrink: 0;
  padding: var(--space-1) var(--space-3);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--accent);
  color: var(--shell-ground);
  font-family: var(--font-body);
  font-size: var(--step--1);
  font-weight: 700;
  cursor: pointer;
  white-space: nowrap;
}

.confirm-btn:disabled {
  opacity: 0.6;
  cursor: default;
}

.entry-actions {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding-top: var(--space-1);
}

.ignore-btn {
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
  white-space: nowrap;
}

.ignore-btn:hover:not(:disabled) {
  border-color: var(--accent);
  color: var(--text);
}

.ignore-btn:disabled {
  opacity: 0.6;
  cursor: default;
}

.error-text {
  color: #d98a78;
  font-size: var(--step--1);
}

.skeleton-rows {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.skeleton-row {
  height: 4em;
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
