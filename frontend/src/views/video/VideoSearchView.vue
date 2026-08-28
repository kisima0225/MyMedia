<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { searchVideo } from '@/api/video'
import type { VideoSearchHit } from '@/api/types'
import VideoCard from '@/components/video/VideoCard.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import ErrorState from '@/components/common/ErrorState.vue'

const route = useRoute()

const status = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
const error = ref<unknown>(null)
const hits = ref<VideoSearchHit[]>([])

const query = computed(() => {
  const raw = route.query.q
  return typeof raw === 'string' ? raw.trim() : ''
})

async function load(): Promise<void> {
  if (!query.value) {
    status.value = 'idle'
    hits.value = []
    return
  }
  status.value = 'loading'
  error.value = null
  try {
    hits.value = await searchVideo(query.value)
    status.value = 'ready'
  } catch (err) {
    error.value = err
    status.value = 'error'
  }
}

onMounted(load)
watch(query, load)

// VideoSearchHit 的 id 字段叫 itemId 不叫 id，不满足 VideoCard 要求的结构——
// 传进去之前映射一次，coverAssetId 这条搜索命中是真的有（不同于目录浏览的 ItemNode）。
const cards = computed(() =>
  hits.value.map((hit) => ({ id: hit.itemId, title: hit.title, coverAssetId: hit.coverAssetId })))
</script>

<template>
  <div class="video-search">
    <EmptyState v-if="status === 'idle'" title="输入关键词开始搜索" hint="在顶栏搜索框按 Ctrl+Enter，只搜视频域" />

    <div v-else-if="status === 'loading'" class="grid">
      <div v-for="n in 8" :key="n" class="skeleton" />
    </div>

    <ErrorState v-else-if="status === 'error'" :error="error" :onRetry="load" />

    <template v-else>
      <!-- 空结果不是错误：这是 ADR-006 的用户可见面——两字中文查询会退化成全表扫描，
           与其让用户以为搜索坏了，不如把这个真实约束直接告诉他。 -->
      <EmptyState
        v-if="cards.length === 0"
        :title="`没有找到匹配「${query}」的视频`"
        hint="试试更长的关键词——少于 3 个字的中文查询会退化成全表扫描"
      />
      <div v-else class="grid">
        <VideoCard v-for="card in cards" :key="card.id" :item="card" />
      </div>
    </template>
  </div>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: var(--space-5) var(--space-4);
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
</style>
