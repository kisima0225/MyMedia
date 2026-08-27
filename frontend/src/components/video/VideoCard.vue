<script setup lang="ts">
import { computed } from 'vue'
import Cover from '@/components/common/Cover.vue'
import { formatDuration } from '@/lib/duration'

// 局部类型而不是直接用 VideoItemSummary：往后其他视图（目录浏览、搜索命中）
// 会传形状不完全一样的对象过来（没有 coverAssetId，或者字段叫 itemId 不叫 id）。
// 只要满足这三个字段，结构类型系统就认——不用每次改这里。
interface VideoCardItem {
  id: number
  title: string
  coverAssetId?: number | null
}

const props = defineProps<{
  item: VideoCardItem
  progress?: { positionSeconds: number; durationSeconds: number | null }
}>()

const progressPercent = computed(() => {
  const p = props.progress
  if (!p || !p.durationSeconds) return 0
  return Math.min(100, (p.positionSeconds / p.durationSeconds) * 100)
})

const metaText = computed(() => {
  const p = props.progress
  if (!p) return null
  const total = p.durationSeconds != null ? formatDuration(p.durationSeconds) : '--:--'
  return `${formatDuration(p.positionSeconds)} / ${total}`
})
</script>

<template>
  <RouterLink :to="{ name: 'video-item', params: { id: item.id } }" class="card">
    <div class="thumb">
      <Cover :assetId="item.coverAssetId" ratio="16/9" :alt="item.title" />
      <div v-if="progress" class="progress">
        <i :style="{ width: progressPercent + '%' }" />
      </div>
    </div>
    <p class="title">{{ item.title }}</p>
    <p v-if="metaText" class="meta">{{ metaText }}</p>
  </RouterLink>
</template>

<style scoped>
.card {
  display: block;
  color: inherit;
  text-decoration: none;
  transition: transform var(--dur-base) var(--ease), box-shadow var(--dur-base) var(--ease);
}

.card:hover,
.card:focus-visible {
  transform: translateY(-2px);
  /* 视频域的高光是"发光"——彩色外发光，模拟暗室里的一块屏。
     图片域用的是黑色落影（--elevation 在两域各自定义）。
     这条区分是整套视觉体系的主轴，不要在这里写死颜色。 */
  box-shadow: var(--elevation);
}

.thumb {
  position: relative;
  aspect-ratio: 16 / 9;
  background: var(--raised);
  border-radius: var(--radius);
  overflow: hidden;
}

/* 进度条贴在缩略图底缘，3px，不占额外行高 */
.progress {
  position: absolute;
  inset: auto 0 0 0;
  height: 3px;
  background: rgb(255 255 255 / 0.16);
}

.progress > i {
  display: block;
  height: 100%;
  background: var(--accent);
}

.title {
  margin-top: var(--space-2);
  font-size: var(--step-0);
  /* 标题最多两行，超出省略。媒体库里的文件名可以任意长，
     让它撑开网格会毁掉整排卡片的对齐 */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.meta {
  margin-top: var(--space-1);
  font-family: var(--font-data);
  font-variant-numeric: tabular-nums;
  font-size: var(--step--1);
  color: var(--dim);
}
</style>
