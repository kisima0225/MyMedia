<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { listFavorites } from '@/api/favorites'
import type { VideoFavoriteEntry, ImageFavoriteEntry } from '@/api/types'
import VideoCard from '@/components/video/VideoCard.vue'
import NodeGrid from '@/components/image/NodeGrid.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import ErrorState from '@/components/common/ErrorState.vue'

const status = ref<'loading' | 'ready' | 'error'>('loading')
const error = ref<unknown>(null)
const videoEntries = ref<VideoFavoriteEntry[]>([])
const imageEntries = ref<ImageFavoriteEntry[]>([])

async function load(): Promise<void> {
  status.value = 'loading'
  error.value = null
  try {
    const [video, image] = await Promise.all([listFavorites('video'), listFavorites('image')])
    videoEntries.value = video
    imageEntries.value = image
    status.value = 'ready'
  } catch (err) {
    error.value = err
    status.value = 'error'
  }
}

onMounted(load)

const isEmpty = computed(
  () => status.value === 'ready' && videoEntries.value.length === 0 && imageEntries.value.length === 0,
)
</script>

<template>
  <div class="favorites-view">
    <div v-if="status === 'loading'" class="skeleton-page">
      <div class="skeleton-grid video-skeleton-grid">
        <div v-for="n in 3" :key="n" class="skeleton video-skeleton" />
      </div>
      <div class="skeleton-grid image-skeleton-grid">
        <div v-for="n in 5" :key="n" class="skeleton image-skeleton" />
      </div>
    </div>

    <ErrorState v-else-if="status === 'error'" :error="error" :onRetry="load" />

    <template v-else>
      <EmptyState
        v-if="isEmpty"
        title="还没有收藏任何内容"
        hint="在视频或图片的详情页点击收藏，就会出现在这里"
      />

      <!-- 某一域零结果时该段整个不渲染——与全局搜索页同一条规矩。收藏可以是文件夹
           （image_favorite 允许收藏任意节点，包括中间目录），图片段里会同时出现书和
           文件夹——BookCard 已经按 readable/browsable 决定跳哪里，不用特殊处理。 -->
      <section v-if="videoEntries.length > 0" data-domain="video" class="result-section">
        <h2 class="section-heading">
          <span>视频</span>
          <span class="rule" aria-hidden="true" />
          <span class="count">{{ videoEntries.length }} 条</span>
        </h2>
        <div class="grid">
          <VideoCard v-for="entry in videoEntries" :key="entry.id" :item="entry" />
        </div>
      </section>

      <section v-if="imageEntries.length > 0" data-domain="image" class="result-section">
        <h2 class="section-heading">
          <span>图片</span>
          <span class="rule" aria-hidden="true" />
          <span class="count">{{ imageEntries.length }} 条</span>
        </h2>
        <NodeGrid :nodes="imageEntries" />
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
