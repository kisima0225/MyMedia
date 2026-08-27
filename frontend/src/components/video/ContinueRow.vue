<script setup lang="ts">
import Cover from '@/components/common/Cover.vue'
import { formatDuration } from '@/lib/duration'
import type { ContinueWatchingEntry } from '@/api/types'

// 不复用 VideoCard：那张卡永远点进 video-item（条目详情），而继续观看要一步到位
// 直接进播放器、带上续播位置——两个组件的落点不同，硬凑到一起反而要在 VideoCard
// 里塞一段条件跳转逻辑，把它的契约搅浑。视觉上照抄同一套卡片语言即可。
defineProps<{ entries: ContinueWatchingEntry[] }>()

function progressPercent(entry: ContinueWatchingEntry): number {
  if (!entry.durationSeconds) return 0
  return Math.min(100, (entry.positionSeconds / entry.durationSeconds) * 100)
}

function title(entry: ContinueWatchingEntry): string {
  return entry.episodeIndex != null
    ? `${entry.itemTitle} E${String(entry.episodeIndex).padStart(2, '0')}`
    : entry.itemTitle
}

function metaText(entry: ContinueWatchingEntry): string {
  const total = entry.durationSeconds != null ? formatDuration(entry.durationSeconds) : '--:--'
  return `${formatDuration(entry.positionSeconds)} / ${total}`
}

// 首屏入场：按索引错开，最多错到第 12 张，再往后一起进场，避免长列表拖出波浪。
function enterDelay(index: number): string {
  return `${Math.min(index, 12) * 20}ms`
}
</script>

<template>
  <section v-if="entries.length > 0" class="continue-row">
    <h2 class="heading">继续观看</h2>
    <div class="scroller">
      <RouterLink
        v-for="(entry, index) in entries"
        :key="entry.fileId"
        :to="{ name: 'video-play', params: { fileId: entry.fileId },
               query: { position: entry.positionSeconds } }"
        class="card"
        :style="{ animationDelay: enterDelay(index) }"
      >
        <div class="thumb">
          <Cover :assetId="entry.coverAssetId" ratio="16/9" :alt="entry.itemTitle" />
          <div class="progress">
            <i :style="{ width: progressPercent(entry) + '%' }" />
          </div>
        </div>
        <p class="title">{{ title(entry) }}</p>
        <p class="meta">{{ metaText(entry) }}</p>
      </RouterLink>
    </div>
  </section>
</template>

<style scoped>
.continue-row {
  margin-bottom: var(--space-6);
}

.heading {
  font-family: var(--display);
  font-weight: 600;
  font-size: var(--step--1);
  letter-spacing: 0.08em;
  color: var(--dim);
  margin-bottom: var(--space-3);
}

.scroller {
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: 280px;
  gap: var(--space-4);
  overflow-x: auto;
  scroll-snap-type: x proximity;
  scrollbar-width: thin;
  padding-bottom: var(--space-2);
}

.card {
  display: block;
  scroll-snap-align: start;
  color: inherit;
  text-decoration: none;
  opacity: 0;
  animation: card-in var(--dur-base) var(--ease) both;
  transition: transform var(--dur-base) var(--ease), box-shadow var(--dur-base) var(--ease);
}

.card:hover,
.card:focus-visible {
  transform: translateY(-2px);
  box-shadow: var(--elevation);
}

.thumb {
  position: relative;
  aspect-ratio: 16 / 9;
  background: var(--raised);
  border-radius: var(--radius);
  overflow: hidden;
}

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
</style>
