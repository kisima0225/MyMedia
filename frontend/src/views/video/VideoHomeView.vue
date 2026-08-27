<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { listItems, continueWatching } from '@/api/video'
import { useLibrariesStore } from '@/stores/libraries'
import { useAuthStore } from '@/stores/auth'
import type { VideoItemSummary, ContinueWatchingEntry } from '@/api/types'
import VideoCard from '@/components/video/VideoCard.vue'
import ContinueRow from '@/components/video/ContinueRow.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import ErrorState from '@/components/common/ErrorState.vue'

const librariesStore = useLibrariesStore()
const auth = useAuthStore()

const items = ref<VideoItemSummary[]>([])
const continueEntries = ref<ContinueWatchingEntry[]>([])
const status = ref<'loading' | 'ready' | 'error'>('loading')
const error = ref<unknown>(null)

async function load(): Promise<void> {
  status.value = 'loading'
  error.value = null
  try {
    // 库列表这里没有调用方缓存过——这是它第一个消费者，每次进页面都刷新一次，
    // 单开一次请求换来的简单胜过在这里再垒一层"是否已加载"的判断。
    const [, itemResult, continueResult] = await Promise.all([
      librariesStore.load(),
      listItems(),
      continueWatching(),
    ])
    items.value = itemResult
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
  librariesStore.videoLibraries
    .map((library) => ({
      library,
      items: items.value.filter((item) => item.libraryId === library.id),
    }))
    .filter((section) => section.items.length > 0),
)

const isEmpty = computed(
  () => status.value === 'ready' && sections.value.length === 0 && continueEntries.value.length === 0,
)

// 首屏入场：按索引错开，最多错到第 12 张，再往后一起进场，避免长列表拖出波浪。
function enterDelay(index: number): string {
  return `${Math.min(index, 12) * 20}ms`
}
</script>

<template>
  <div class="video-home">
    <div v-if="status === 'loading'" class="grid">
      <div v-for="n in 10" :key="n" class="skeleton" />
    </div>

    <ErrorState v-else-if="status === 'error'" :error="error" :onRetry="load" />

    <template v-else>
      <ContinueRow :entries="continueEntries" />

      <div v-if="isEmpty" class="empty-wrap">
        <EmptyState title="这个媒体库还没有内容" hint="去『媒体库管理』开始一次扫描" />
        <RouterLink v-if="auth.isAdmin" :to="{ name: 'admin-libraries' }" class="jump">
          去媒体库管理
        </RouterLink>
      </div>

      <section v-for="section in sections" :key="section.library.id" class="library-section">
        <h2 class="heading">
          <span>{{ section.library.name }}</span>
          <span class="rule" aria-hidden="true" />
        </h2>
        <div class="grid">
          <div
            v-for="(item, index) in section.items"
            :key="item.id"
            class="enter"
            :style="{ animationDelay: enterDelay(index) }"
          >
            <VideoCard :item="item" />
          </div>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: var(--space-5) var(--space-4);
}

.library-section {
  margin-bottom: var(--space-6);
}

.heading {
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

.enter {
  opacity: 0;
  animation: card-in var(--dur-base) var(--ease) both;
}

@keyframes card-in {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.skeleton {
  aspect-ratio: 16 / 9;
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
</style>
