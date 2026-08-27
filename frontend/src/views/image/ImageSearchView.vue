<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { searchImage } from '@/api/image'
import type { ImageSearchHit } from '@/api/types'
import NodeGrid from '@/components/image/NodeGrid.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import ErrorState from '@/components/common/ErrorState.vue'

const route = useRoute()

const status = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
const error = ref<unknown>(null)
const hits = ref<ImageSearchHit[]>([])

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
    hits.value = await searchImage(query.value)
    status.value = 'ready'
  } catch (err) {
    error.value = err
    status.value = 'error'
  }
}

onMounted(load)
watch(query, load)

// ImageSearchHit 形状比 BookCard 的 node prop 窄、字段名也不同（nodeId 不叫 id），
// 且刻意把 browsable 恒置为 false：BookCard 的 target 只在 readable === true 时才会
// 去看 browsable 来决定「进阅读器」还是「进浏览页」——搜索命中如果可读就该直接进
// 阅读器，不该因为它凑巧也 browsable 而被带去浏览页；readable === false 时
// browsable 的值本就不影响路由分支，随便填。childNodeCount 同理，不会被用到。
const cards = computed(() =>
  hits.value.map((hit) => ({
    id: hit.nodeId,
    displayName: hit.title ?? hit.name,
    coverAssetId: hit.coverAssetId,
    readable: hit.readable,
    browsable: false,
    totalPageCount: hit.totalPageCount,
    childNodeCount: 0,
  })))
</script>

<template>
  <div class="image-search">
    <EmptyState v-if="status === 'idle'" title="输入关键词开始搜索" hint="在顶栏搜索框按 Ctrl+Enter，只搜图片域" />

    <div v-else-if="status === 'loading'" class="grid">
      <div v-for="n in 10" :key="n" class="skeleton" />
    </div>

    <ErrorState v-else-if="status === 'error'" :error="error" :onRetry="load" />

    <template v-else>
      <!-- 空结果不是错误，与视频侧对称（ADR-006）：短查询会退化成全表扫描，
           与其让用户以为搜索坏了，不如把这个真实约束直接告诉他。 -->
      <EmptyState
        v-if="cards.length === 0"
        :title="`没有找到匹配「${query}」的图集`"
        hint="试试更长的关键词——少于 3 个字的中文查询会退化成全表扫描"
      />
      <NodeGrid v-else :nodes="cards" />
    </template>
  </div>
</template>

<style scoped>
.grid {
  columns: 5 200px;
  column-gap: var(--space-4);
}

.grid > * {
  break-inside: avoid;
  margin-bottom: var(--space-5);
}

.skeleton {
  aspect-ratio: 2 / 3;
  background: var(--raised);
  border-radius: var(--radius);
  animation: skeleton-breathe 1.4s ease-in-out infinite;
}

@media (max-width: 900px) {
  .grid {
    columns: 3 140px;
  }
}

@media (max-width: 560px) {
  .grid {
    columns: 2 130px;
  }
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
