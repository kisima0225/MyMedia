<script setup lang="ts">
import { computed, ref } from 'vue'
import { formatDuration } from '@/lib/duration'
import { cueAt, type SpriteCue } from '@/lib/sprite'

const props = defineProps<{
  currentTime: number
  duration: number
  cues: SpriteCue[]
  spriteUrl: string | null
}>()

const emit = defineEmits<{ seek: [seconds: number] }>()

const track = ref<HTMLElement | null>(null)
const dragging = ref(false)

interface HoverState {
  seconds: number
  /** 相对轨道左边缘的像素偏移，同时喂给 peekStyle 的居中计算。 */
  x: number
  trackWidth: number
}
const hover = ref<HoverState | null>(null)

const percent = computed(() => {
  if (!Number.isFinite(props.duration) || props.duration <= 0) return 0
  return Math.min(100, Math.max(0, (props.currentTime / props.duration) * 100))
})

const hoverCue = computed<SpriteCue | null>(() =>
  hover.value ? cueAt(props.cues, hover.value.seconds) : null,
)

// ★ 签名元素：悬停时浮出该时刻的预览帧。
// 雪碧图是一张 10×10 的大图，用 background-position 把对应格子推到窗口里，
// 整个过程零网络请求——图早在第一次悬停时就整张下好了。
const frameStyle = computed(() => {
  const cue = hoverCue.value
  if (!cue || !props.spriteUrl) return {}
  return {
    width: `${cue.w}px`,
    height: `${cue.h}px`,
    backgroundImage: `url(${props.spriteUrl})`,
    // 把雪碧图整体往左上推，让目标格子正好落进这个 w×h 的窗口里
    backgroundPosition: `-${cue.x}px -${cue.y}px`,
    backgroundRepeat: 'no-repeat',
  }
})

// 预览框水平居中到指针处，但夹在轨道两端之内——否则拖到最左边时
// 预览框会飘出屏幕。
const peekStyle = computed(() => {
  const h = hover.value
  const cue = hoverCue.value
  if (!h || !cue) return {}
  const half = cue.w / 2
  const left = Math.min(Math.max(h.x, half), Math.max(h.trackWidth - half, half))
  return { left: `${left}px` }
})

function updateHover(clientX: number): number | null {
  const el = track.value
  if (!el || !Number.isFinite(props.duration) || props.duration <= 0) return null
  const rect = el.getBoundingClientRect()
  if (rect.width <= 0) return null
  const x = Math.min(Math.max(clientX - rect.left, 0), rect.width)
  const seconds = (x / rect.width) * props.duration
  hover.value = { seconds, x, trackWidth: rect.width }
  return seconds
}

function onHover(event: PointerEvent): void {
  const seconds = updateHover(event.clientX)
  // 拖拽中指针移动也要连续跳转，而不是只在按下的瞬间跳一次。
  if (seconds != null && dragging.value) {
    emit('seek', seconds)
  }
}

function onSeek(event: PointerEvent): void {
  dragging.value = true
  // 捕获指针：即使拖到轨道外面，move/up 事件仍然会继续投给这个元素。
  ;(event.currentTarget as Element).setPointerCapture?.(event.pointerId)
  const seconds = updateHover(event.clientX)
  if (seconds != null) emit('seek', seconds)
}

function onRelease(event: PointerEvent): void {
  if (!dragging.value) return
  dragging.value = false
  ;(event.currentTarget as Element).releasePointerCapture?.(event.pointerId)
}

function clampTime(seconds: number): number {
  const max = Number.isFinite(props.duration) ? props.duration : 0
  return Math.min(Math.max(seconds, 0), max)
}

// ←/→ 退进 5 秒，Shift + 方向键 1 秒，Home/End 跳首尾。
function onKey(event: KeyboardEvent): void {
  const step = event.shiftKey ? 1 : 5
  switch (event.key) {
    case 'ArrowLeft':
      emit('seek', clampTime(props.currentTime - step))
      event.preventDefault()
      break
    case 'ArrowRight':
      emit('seek', clampTime(props.currentTime + step))
      event.preventDefault()
      break
    case 'Home':
      emit('seek', 0)
      event.preventDefault()
      break
    case 'End':
      emit('seek', clampTime(props.duration))
      event.preventDefault()
      break
  }
}
</script>

<template>
  <div class="scrub" ref="track" @pointermove="onHover" @pointerleave="hover = null"
       @pointerdown="onSeek" @pointerup="onRelease" @pointercancel="onRelease"
       role="slider" tabindex="0"
       :aria-valuemin="0" :aria-valuemax="duration" :aria-valuenow="currentTime"
       aria-label="播放进度" @keydown="onKey">
    <div class="rail"><i class="fill" :style="{ width: percent + '%' }" /></div>
    <div class="head" :style="{ left: percent + '%' }" />

    <!-- ★ 签名元素：悬停时浮出该时刻的预览帧。
         雪碧图是一张 10×10 的大图，用 background-position 把对应格子推到窗口里，
         整个过程零网络请求——图早在第一次悬停时就整张下好了。 -->
    <figure v-if="hover && hoverCue && spriteUrl" class="peek" :style="peekStyle">
      <div class="frame" :style="frameStyle" />
      <figcaption class="num">{{ formatDuration(hover.seconds) }}</figcaption>
    </figure>
  </div>
</template>

<style scoped>
.scrub {
  position: relative;
  display: flex;
  align-items: center;
  height: 20px;
  cursor: pointer;
  touch-action: none;
}

.scrub:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 4px;
  border-radius: var(--radius);
}

.rail {
  position: relative;
  width: 100%;
  height: 4px;
  border-radius: 999px;
  background: rgb(255 255 255 / 0.16);
  overflow: hidden;
}

.fill {
  position: absolute;
  inset: 0 auto 0 0;
  display: block;
  background: var(--accent);
}

.head {
  position: absolute;
  top: 50%;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: var(--accent);
  box-shadow: var(--elevation);
  transform: translate(-50%, -50%);
  opacity: 0;
  pointer-events: none;
  transition: opacity var(--dur-fast) var(--ease);
}

.scrub:hover .head,
.scrub:focus-visible .head {
  opacity: 1;
}

.peek {
  position: absolute;
  bottom: calc(100% + var(--space-3));
  transform: translateX(-50%);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-1);
  margin: 0;
  padding: var(--space-1);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--raised);
  box-shadow: var(--elevation);
  pointer-events: none;
}

.frame {
  display: block;
  border-radius: calc(var(--radius) - 2px);
  overflow: hidden;
}

.num {
  font-family: var(--font-data);
  font-variant-numeric: tabular-nums;
  font-size: var(--step--1);
  color: var(--text);
}
</style>
