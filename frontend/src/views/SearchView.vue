<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { apiGet } from '@/api/client'
import type { VideoSearchHit, ImageSearchHit } from '@/api/types'
import VideoCard from '@/components/video/VideoCard.vue'
import NodeGrid from '@/components/image/NodeGrid.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import ErrorState from '@/components/common/ErrorState.vue'

// 全局搜索是两个域唯一的交汇点，结果分区展示、不混排（spec §5.4）——两段各自
// 包一层 data-domain，CSS 令牌是按属性选择器定义的，套在任意元素上都生效，
// 不需要改 tokens.css。GlobalSearchController 的 limit 默认就是 20，
// 与两个域各自的搜索页同一个默认值，这里不用再显式传一次。
interface GlobalSearchResponse {
  query: string
  video: VideoSearchHit[]
  image: ImageSearchHit[]
}

const route = useRoute()

const status = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
const error = ref<unknown>(null)
const result = ref<GlobalSearchResponse | null>(null)

const query = computed(() => {
  const raw = route.query.q
  return typeof raw === 'string' ? raw.trim() : ''
})

async function load(): Promise<void> {
  if (!query.value) {
    status.value = 'idle'
    result.value = null
    return
  }
  status.value = 'loading'
  error.value = null
  try {
    result.value = await apiGet<GlobalSearchResponse>(`/api/search?q=${encodeURIComponent(query.value)}`)
    status.value = 'ready'
  } catch (err) {
    error.value = err
    status.value = 'error'
  }
}

onMounted(load)
watch(query, load)

// VideoSearchHit 的 id 字段叫 itemId 不叫 id——与 VideoSearchView 同一条映射。
const videoCards = computed(() =>
  (result.value?.video ?? []).map((hit) => ({ id: hit.itemId, title: hit.title, coverAssetId: hit.coverAssetId })))

// ImageSearchHit 映射进 BookCard 的局部类型——与 ImageSearchView 同一条规则：browsable
// 恒置 false（target 只在 readable === true 时才看 browsable，可读命中该直接进阅读器，
// 不该因为它凑巧也 browsable 被带去浏览页；readable === false 时 browsable 不影响路由分支）。
const imageCards = computed(() =>
  (result.value?.image ?? []).map((hit) => ({
    id: hit.nodeId,
    displayName: hit.title ?? hit.name,
    coverAssetId: hit.coverAssetId,
    readable: hit.readable,
    browsable: false,
    totalPageCount: hit.totalPageCount,
    childNodeCount: 0,
  })))

const isEmpty = computed(
  () => status.value === 'ready' && videoCards.value.length === 0 && imageCards.value.length === 0,
)
</script>

<template>
  <div class="search-view">
    <EmptyState
      v-if="status === 'idle'"
      title="输入关键词开始搜索"
      hint="在顶栏搜索框按 Enter，同时搜索视频与图片两个域"
    />

    <div v-else-if="status === 'loading'" class="skeleton-page">
      <div class="skeleton-grid video-skeleton-grid">
        <div v-for="n in 3" :key="n" class="skeleton video-skeleton" />
      </div>
      <div class="skeleton-grid image-skeleton-grid">
        <div v-for="n in 5" :key="n" class="skeleton image-skeleton" />
      </div>
    </div>

    <ErrorState v-else-if="status === 'error'" :error="error" :onRetry="load" />

    <template v-else>
      <!-- 空结果不是错误（ADR-006）：与两个域自己的搜索页同一条规矩。 -->
      <EmptyState
        v-if="isEmpty"
        :title="`没有找到匹配「${query}」的内容`"
        hint="试试更长的关键词——少于 3 个字的中文查询会退化成全表扫描"
      />

      <!-- 某一域零结果时该段整个不渲染——这一屏最能说明整套视觉体系：
           同一个页面上，两个域的卡片看起来就该是两种东西。 -->
      <section v-if="videoCards.length > 0" data-domain="video" class="result-section">
        <h2 class="section-heading">
          <span>视频</span>
          <span class="rule" aria-hidden="true" />
          <span class="count">{{ videoCards.length }} 条</span>
        </h2>
        <div class="grid">
          <VideoCard v-for="card in videoCards" :key="card.id" :item="card" />
        </div>
      </section>

      <section v-if="imageCards.length > 0" data-domain="image" class="result-section">
        <h2 class="section-heading">
          <span>图片</span>
          <span class="rule" aria-hidden="true" />
          <span class="count">{{ imageCards.length }} 条</span>
        </h2>
        <NodeGrid :nodes="imageCards" />
      </section>
    </template>
  </div>
</template>

<style scoped>
.result-section + .result-section {
  margin-top: var(--space-6);
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

.count {
  font-family: var(--font-data);
  font-variant-numeric: tabular-nums;
  font-size: var(--step--1);
  font-weight: 400;
  color: var(--dim);
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: var(--space-5) var(--space-4);
}

.skeleton-page {
  display: flex;
  flex-direction: column;
  gap: var(--space-6);
}

.skeleton-grid {
  display: grid;
  gap: var(--space-5) var(--space-4);
}

.video-skeleton-grid {
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
}

.image-skeleton-grid {
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
}

.skeleton {
  background: var(--raised);
  border-radius: var(--radius);
  animation: skeleton-breathe 1.4s ease-in-out infinite;
}

.video-skeleton {
  aspect-ratio: 16 / 9;
}

.image-skeleton {
  aspect-ratio: 2 / 3;
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
