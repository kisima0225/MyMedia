<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import type { RouteLocationRaw } from 'vue-router'
import { videoPreview, continueWatching, itemDetail } from '@/api/video'
import { mediaUrl, assetUrlAsync } from '@/api/media'
import { parseVtt, type SpriteCue } from '@/lib/sprite'
import ErrorState from '@/components/common/ErrorState.vue'
import VideoPlayer from '@/components/video/VideoPlayer.vue'
import type { ContinueWatchingEntry, VideoFileSummary, VideoPreviewView } from '@/api/types'

// props: true（router/index.ts）把 :fileId 直接注入成这个字符串 prop——路由参数
// 永远是字符串，转成数字的责任留在这个视图里，不往下游泄露。
const props = defineProps<{ fileId: string }>()
const fileId = computed(() => Number(props.fileId))

const status = ref<'loading' | 'ready' | 'error'>('loading')
const error = ref<unknown>(null)

const src = ref('')
const cues = ref<SpriteCue[]>([])
const spriteUrl = ref<string | null>(null)
const resumePosition = ref<number | null>(null)
const prevTarget = ref<RouteLocationRaw | null>(null)
const nextTarget = ref<RouteLocationRaw | null>(null)

const byEpisodeOrder = (a: VideoFileSummary, b: VideoFileSummary): number =>
  (a.episodeIndex ?? Infinity) - (b.episodeIndex ?? Infinity)

/**
 * 雪碧图预览是锦上添花，不是播放的前提条件——spriteAssetId/spriteVttAssetId
 * 缺失（还没生成）或请求本身失败，都只是让 ScrubBar 少一个悬停预览，
 * 不该连累视频播放不出来。
 */
async function loadSpritePreview(preview: VideoPreviewView): Promise<void> {
  try {
    const [vttText, url] = await Promise.all([
      preview.spriteVttAssetId != null
        ? assetUrlAsync(preview.spriteVttAssetId).then((u) => fetch(u).then((r) => r.text()))
        : Promise.resolve(null),
      preview.spriteAssetId != null ? assetUrlAsync(preview.spriteAssetId) : Promise.resolve(null),
    ])
    cues.value = vttText != null ? parseVtt(vttText) : []
    spriteUrl.value = url
  } catch (err) {
    console.warn('雪碧图预览加载失败，进度条将不显示悬停预览', err)
    cues.value = []
    spriteUrl.value = null
  }
}

/**
 * 上一集/下一集需要先知道这个 fileId 属于哪个 itemId，但路由参数和
 * videoPreview() 都不带 itemId——唯一现成的桥是 continueWatching()：
 * 如果这个文件已经报过进度，它会出现在那份列表里，带着 itemId。
 *
 * 一个从没播放过的文件不会出现在 continueWatching() 里，这种情况下
 * itemId 无从查起——上一集/下一集按钮就禁用，而不是为了这个任务
 * 新开一个把 fileId 映射到 itemId 的后端端点（这个计划的范围明确是纯前端）。
 */
async function resolveSiblingEpisodes(id: number, progress: ContinueWatchingEntry[]): Promise<void> {
  const entry = progress.find((e) => e.fileId === id)
  if (!entry) return
  try {
    const detail = await itemDetail(entry.itemId)
    // 与 EpisodeList 相同的顺序规则：GROUPED 结构按 groupIndex 分组、组内按
    // episodeIndex 排序后拼接，未归组的文件追加在最后（对应 EpisodeList 的
    // "其他文件" 兜底节）；FLAT 结构直接按 episodeIndex 排序。
    const ordered = detail.item.structure === 'GROUPED'
      ? [
          ...[...detail.groups]
            .sort((a, b) => a.groupIndex - b.groupIndex)
            .flatMap((group) => detail.files.filter((f) => f.groupId === group.id).sort(byEpisodeOrder)),
          ...detail.files.filter((f) => f.groupId == null).sort(byEpisodeOrder),
        ]
      : [...detail.files].sort(byEpisodeOrder)

    const index = ordered.findIndex((f) => f.id === id)
    if (index === -1) return
    const prev = ordered[index - 1]
    const next = ordered[index + 1]
    prevTarget.value = prev ? { name: 'video-play', params: { fileId: prev.id } } : null
    nextTarget.value = next ? { name: 'video-play', params: { fileId: next.id } } : null
  } catch {
    // 侧边导航失败不该拖垮整个播放页——保持按钮禁用即可
    prevTarget.value = null
    nextTarget.value = null
  }
}

async function load(): Promise<void> {
  status.value = 'loading'
  error.value = null
  src.value = ''
  cues.value = []
  spriteUrl.value = null
  resumePosition.value = null
  prevTarget.value = null
  nextTarget.value = null

  const id = fileId.value
  try {
    const preview = await videoPreview(id)

    // 先拿票据再赋 src：顺序反了，<video> 会先发一次无票据请求吃 401，
    // 某些浏览器会就此把这个 src 标记为失败不再重试。
    src.value = await mediaUrl(`/api/video/stream/${id}`)

    await loadSpritePreview(preview)

    const progress = await continueWatching()
    const entry = progress.find((e) => e.fileId === id)
    resumePosition.value = entry && !entry.completed ? entry.positionSeconds : null
    await resolveSiblingEpisodes(id, progress)

    status.value = 'ready'
  } catch (err) {
    error.value = err
    status.value = 'error'
  }
}

onMounted(load)
watch(fileId, load)
</script>

<template>
  <div class="player-view">
    <div v-if="status === 'loading'" class="skeleton" />

    <ErrorState v-else-if="status === 'error'" :error="error" :onRetry="load" />

    <!-- :key="fileId"：切上一集/下一集时强制重建整个播放器实例，避免旧
         <video> 元素的播放状态（进度、音量以外的暂停/播放）串到新文件上。 -->
    <VideoPlayer
      v-else
      :key="fileId"
      :fileId="fileId"
      :src="src"
      :cues="cues"
      :spriteUrl="spriteUrl"
      :resumePosition="resumePosition"
      :prevTarget="prevTarget"
      :nextTarget="nextTarget"
    />
  </div>
</template>

<style scoped>
.player-view {
  max-width: 1400px;
  margin: 0 auto;
}

.skeleton {
  aspect-ratio: 16 / 9;
  border-radius: var(--radius);
  background: var(--raised);
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
