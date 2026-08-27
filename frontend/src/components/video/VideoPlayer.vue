<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { RouteLocationRaw } from 'vue-router'
import { formatDuration } from '@/lib/duration'
import { createThrottle } from '@/lib/throttle'
import { recordProgress } from '@/api/video'
import type { SpriteCue } from '@/lib/sprite'
import ScrubBar from './ScrubBar.vue'

const props = defineProps<{
  fileId: number
  src: string
  cues: SpriteCue[]
  spriteUrl: string | null
  /** continueWatching() 里查到的续播位置；null 表示从头播。 */
  resumePosition: number | null
  prevTarget: RouteLocationRaw | null
  nextTarget: RouteLocationRaw | null
}>()

const container = ref<HTMLElement | null>(null)
const video = ref<HTMLVideoElement | null>(null)

const playing = ref(false)
const currentTime = ref(0)
const duration = ref(0)
const volumeInput = ref(1)
const fullscreen = ref(false)
const controlsVisible = ref(true)

// 用户已经移动过播放头才生效一次——loadedmetadata 在同一个 <video> 实例上
// 理论上只会因为换 src 触发，但 PlayerView 用 :key="fileId" 保证了每次换集
// 都是全新实例，这个标记不需要跨 fileId 复位。
let resumed = false
let idleTimer: ReturnType<typeof setTimeout> | null = null

// timeupdate 每秒 4 次，节流到 5 秒一次——两小时的片子从三万次请求
// 压到几十次，代价是关标签页时最多丢 5 秒进度，由 flush() 补上。
const report = createThrottle(
  (seconds: number) => void recordProgress(props.fileId, Math.floor(seconds), duration.value || undefined),
  5000,
)

const showControls = computed(() => controlsVisible.value || !playing.value)

function armIdleTimer(): void {
  if (idleTimer) clearTimeout(idleTimer)
  idleTimer = setTimeout(() => {
    if (playing.value) controlsVisible.value = false
  }, 2500)
}

function onActivity(): void {
  controlsVisible.value = true
  if (playing.value) armIdleTimer()
}

function togglePlay(): void {
  const v = video.value
  if (!v) return
  if (v.paused) void v.play()
  else v.pause()
}

function seek(seconds: number): void {
  const v = video.value
  if (!v) return
  const clamped = Math.min(Math.max(seconds, 0), duration.value || 0)
  v.currentTime = clamped
  currentTime.value = clamped
}

// 必须等 loadedmetadata——之前设 currentTime 会被忽略。
function onLoadedMetadata(): void {
  const v = video.value
  if (!v) return
  duration.value = v.duration
  if (!resumed) {
    resumed = true
    const pos = props.resumePosition
    if (pos != null && Number.isFinite(pos) && pos > 0 && pos < v.duration) {
      v.currentTime = pos
      currentTime.value = pos
    }
  }
}

function onDurationChange(): void {
  if (video.value) duration.value = video.value.duration
}

function onTimeUpdate(): void {
  const v = video.value
  if (!v) return
  currentTime.value = v.currentTime
  report.call(v.currentTime)
}

function onPlay(): void {
  playing.value = true
  armIdleTimer()
}

// 这三个时机必须立刻补发，否则用户关掉标签页时最后 5 秒的进度就丢了。
function onPause(): void {
  playing.value = false
  if (idleTimer) clearTimeout(idleTimer)
  controlsVisible.value = true
  report.flush()
}

function onVisibilityChange(): void {
  if (document.hidden) report.flush()
}

function toggleFullscreen(): void {
  if (!document.fullscreenElement) {
    void container.value?.requestFullscreen()
  } else {
    void document.exitFullscreen()
  }
}

function onFullscreenChange(): void {
  fullscreen.value = document.fullscreenElement === container.value
}

watch(volumeInput, (v) => {
  if (video.value) video.value.volume = v
}, { immediate: true })

onMounted(() => {
  document.addEventListener('visibilitychange', onVisibilityChange)
  document.addEventListener('fullscreenchange', onFullscreenChange)
})

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisibilityChange)
  document.removeEventListener('fullscreenchange', onFullscreenChange)
  if (idleTimer) clearTimeout(idleTimer)
  report.flush()
})
</script>

<template>
  <div
    ref="container"
    class="player"
    :class="{ 'controls-hidden': !showControls }"
    @mousemove="onActivity"
  >
    <video
      ref="video"
      class="video"
      :src="src"
      playsinline
      @loadedmetadata="onLoadedMetadata"
      @durationchange="onDurationChange"
      @timeupdate="onTimeUpdate"
      @play="onPlay"
      @pause="onPause"
      @click="togglePlay"
    />

    <div class="controls">
      <ScrubBar
        class="scrub-row"
        :currentTime="currentTime"
        :duration="duration"
        :cues="cues"
        :spriteUrl="spriteUrl"
        @seek="seek"
      />

      <div class="row">
        <button type="button" class="ctrl" @click="togglePlay">
          {{ playing ? '暂停' : '播放' }}
        </button>

        <RouterLink v-if="prevTarget" :to="prevTarget" class="ctrl">上一集</RouterLink>
        <button v-else type="button" class="ctrl" disabled>上一集</button>

        <RouterLink v-if="nextTarget" :to="nextTarget" class="ctrl">下一集</RouterLink>
        <button v-else type="button" class="ctrl" disabled>下一集</button>

        <span class="time">{{ formatDuration(currentTime) }} / {{ formatDuration(duration) }}</span>

        <span class="spacer" />

        <label class="volume">
          <span class="sr-only">音量</span>
          <input v-model.number="volumeInput" type="range" min="0" max="1" step="0.01" />
        </label>

        <button type="button" class="ctrl" @click="toggleFullscreen">
          {{ fullscreen ? '退出全屏' : '全屏' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.player {
  position: relative;
  width: 100%;
  aspect-ratio: 16 / 9;
  background: #000;
  border-radius: var(--radius);
  overflow: hidden;
  outline: none;
}

.player:fullscreen {
  aspect-ratio: unset;
  width: 100vw;
  height: 100vh;
  border-radius: 0;
}

.video {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
  background: #000;
  cursor: pointer;
}

.controls {
  position: absolute;
  inset: auto 0 0 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-4) var(--space-4) var(--space-3);
  background: linear-gradient(to top, rgb(0 0 0 / 0.8), transparent);
  opacity: 1;
  transition: opacity var(--dur-base) var(--ease);
}

.player.controls-hidden .controls {
  opacity: 0;
  pointer-events: none;
}

.player.controls-hidden {
  cursor: none;
}

.scrub-row {
  padding: 0 var(--space-1);
}

.row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.ctrl {
  padding: var(--space-1) var(--space-3);
  border: 1px solid rgb(255 255 255 / 0.24);
  border-radius: var(--radius);
  background: rgb(255 255 255 / 0.08);
  color: var(--text);
  font-family: var(--font-body);
  font-size: var(--step--1);
  font-weight: 600;
  text-decoration: none;
  cursor: pointer;
  transition: border-color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}

.ctrl:hover:not(:disabled) {
  border-color: var(--accent);
  background: rgb(255 255 255 / 0.14);
}

.ctrl:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.time {
  font-family: var(--font-data);
  font-variant-numeric: tabular-nums;
  font-size: var(--step--1);
  color: var(--text);
  white-space: nowrap;
}

.spacer {
  flex: 1;
}

.volume {
  display: flex;
  align-items: center;
}

.volume input[type='range'] {
  width: 96px;
  accent-color: var(--accent);
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
</style>
