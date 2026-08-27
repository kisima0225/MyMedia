<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { listRoots, continueReading } from '@/api/image'
import { useLibrariesStore } from '@/stores/libraries'
import { useAuthStore } from '@/stores/auth'
import type { ImageNodeSummary, ContinueReadingEntry } from '@/api/types'
import BookCard from '@/components/image/BookCard.vue'
import NodeGrid from '@/components/image/NodeGrid.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import ErrorState from '@/components/common/ErrorState.vue'

const librariesStore = useLibrariesStore()
const auth = useAuthStore()

const roots = ref<ImageNodeSummary[]>([])
const continueEntries = ref<ContinueReadingEntry[]>([])
const status = ref<'loading' | 'ready' | 'error'>('loading')
const error = ref<unknown>(null)

async function load(): Promise<void> {
  status.value = 'loading'
  error.value = null
  try {
    // 库列表这里没有调用方缓存过——这是它第一个消费者，每次进页面都刷新一次，
    // 单开一次请求换来的简单胜过在这里再垒一层"是否已加载"的判断（与视频首页同一条取舍）。
    const [, rootResult, continueResult] = await Promise.all([
      librariesStore.load(),
      listRoots(),
      continueReading(),
    ])
    roots.value = rootResult
    continueEntries.value = continueResult
    status.value = 'ready'
  } catch (err) {
    error.value = err
    status.value = 'error'
  }
}

onMounted(load)

// 按媒体库分段：只保留有内容的库，顺序跟媒体库列表一致。
const sections = computed(() =>
  librariesStore.imageLibraries
    .map((library) => ({
      library,
      nodes: roots.value.filter((node) => node.libraryId === library.id),
    }))
    .filter((section) => section.nodes.length > 0),
)

const isEmpty = computed(
  () => status.value === 'ready' && sections.value.length === 0 && continueEntries.value.length === 0,
)

// ContinueReadingEntry 没有 readable/browsable/childNodeCount——续读记录本来就是
// "读到一半"的记录，一定可读，且要直接进阅读器而不经过浏览页，这里补成固定值即可，
// 不需要为此改 BookCard 的 prop 类型。
function continueReadingNode(entry: ContinueReadingEntry) {
  return {
    id: entry.nodeId,
    displayName: entry.nodeTitle,
    coverAssetId: entry.coverAssetId,
    readable: true,
    browsable: false,
    totalPageCount: entry.totalPageCount,
    childNodeCount: 0,
  }
}
</script>

<template>
  <div class="image-home">
    <div v-if="status === 'loading'" class="skeleton-grid">
      <div v-for="n in 12" :key="n" class="skeleton" />
    </div>

    <ErrorState v-else-if="status === 'error'" :error="error" :onRetry="load" />

    <template v-else>
      <section v-if="continueEntries.length > 0" class="continue-row">
        <h2 class="continue-heading">继续阅读</h2>
        <div class="scroller">
          <div v-for="entry in continueEntries" :key="entry.nodeId" class="scroller-item">
            <BookCard
              :node="continueReadingNode(entry)"
              :progress="{ pageIndex: entry.pageIndex, totalPageCount: entry.totalPageCount }"
            />
          </div>
        </div>
      </section>

      <div v-if="isEmpty" class="empty-wrap">
        <EmptyState title="这个媒体库还没有内容" hint="去『媒体库管理』开始一次扫描" />
        <RouterLink v-if="auth.isAdmin" :to="{ name: 'admin-libraries' }" class="jump">
          去媒体库管理
        </RouterLink>
      </div>

      <section v-for="section in sections" :key="section.library.id" class="library-section">
        <h2 class="section-heading">
          <span>{{ section.library.name }}</span>
          <span class="rule" aria-hidden="true" />
        </h2>
        <NodeGrid :nodes="section.nodes" />
      </section>
    </template>
  </div>
</template>

<style scoped>
.continue-row {
  margin-bottom: var(--space-6);
}

.continue-heading {
  font-family: var(--display);
  font-weight: 500;
  font-size: var(--step-1);
  color: var(--text);
  margin-bottom: var(--space-4);
}

.scroller {
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: 160px;
  gap: var(--space-4);
  overflow-x: auto;
  scroll-snap-type: x proximity;
  scrollbar-width: thin;
  padding-bottom: var(--space-2);
}

.scroller-item {
  scroll-snap-align: start;
}

.library-section {
  margin-bottom: var(--space-6);
}

.section-heading {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
  font-family: var(--display);
  font-weight: 600;
  font-size: var(--step-1);
  color: var(--text);
}

.rule {
  flex: 1;
  height: 1px;
  background: var(--line);
}

.empty-wrap {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-3);
}

.jump {
  padding: var(--space-2) var(--space-4);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--raised);
  color: var(--text);
  font-family: var(--font-body);
  font-size: var(--step-0);
  font-weight: 600;
  text-decoration: none;
  transition: border-color var(--dur-fast) var(--ease);
}

.jump:hover {
  border-color: var(--accent);
}

.skeleton-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: var(--space-5) var(--space-4);
}

.skeleton {
  aspect-ratio: 2 / 3;
  background: var(--raised);
  border-radius: var(--radius);
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
