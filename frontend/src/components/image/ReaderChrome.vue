<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { favorite, unfavorite, listFavorites } from '@/api/favorites'
import { visiblePages, type ReaderState, type ReaderMode, type ReaderDirection } from '@/lib/reader'

const props = defineProps<{
  state: ReaderState
  nodeId: number
  /** 显隐完全由 ReaderView 的鼠标静止计时器与中间点击区决定——这个组件本身
   *  不跑计时器，只负责在拿到焦点时用 CSS 把 `visible=false` 的效果盖掉。 */
  visible: boolean
}>()

const emit = defineEmits<{
  next: []
  prev: []
  goTo: [index: number]
  setMode: [mode: ReaderMode]
  setDirection: [direction: ReaderDirection]
}>()

const router = useRouter()

function goBack(): void {
  router.back()
}

// 页码展示永远按页序升序（12–13），不跟着 rtl 的显示顺序左右对调——
// visiblePages() 返回的 [3, 2] 是「屏幕上从左到右摆哪几页」，页码牌回答的是
// 另一个问题「这一屏是第几页到第几页」，两者不是一回事。
const pageLabel = computed(() => {
  const pages = [...visiblePages(props.state)].sort((a, b) => a - b)
  if (pages.length === 0) return `0 / ${props.state.total}`
  const lo = pages[0] + 1
  const hi = pages[pages.length - 1] + 1
  return pages.length > 1 ? `${lo}–${hi} / ${props.state.total}` : `${lo} / ${props.state.total}`
})

function onSlider(event: Event): void {
  const value = Number((event.target as HTMLInputElement).value)
  emit('goTo', value)
}

const MODE_OPTIONS: { value: ReaderMode; label: string }[] = [
  { value: 'single', label: '单页' },
  { value: 'double', label: '双页' },
  { value: 'continuous', label: '连续' },
]
const DIRECTION_OPTIONS: { value: ReaderDirection; label: string }[] = [
  { value: 'ltr', label: '从左往右' },
  { value: 'rtl', label: '从右往左' },
]

// 收藏：与 ItemDetailView 同一条规矩——挂载时用 listFavorites('image') 查一遍，
// 按钮只做乐观切换，失败就静默回滚（这里是浮层控制条，没有版面放错误文案）。
const favorited = ref(false)
const favoriteBusy = ref(false)

async function loadFavorite(): Promise<void> {
  try {
    const entries = await listFavorites('image')
    favorited.value = entries.some((entry) => entry.id === props.nodeId)
  } catch {
    favorited.value = false
  }
}

onMounted(loadFavorite)
watch(() => props.nodeId, loadFavorite)

async function toggleFavorite(): Promise<void> {
  if (favoriteBusy.value) return
  favoriteBusy.value = true
  const next = !favorited.value
  try {
    if (next) await favorite('image', props.nodeId)
    else await unfavorite('image', props.nodeId)
    favorited.value = next
  } catch (err) {
    console.warn('收藏状态切换失败', err)
  } finally {
    favoriteBusy.value = false
  }
}
</script>

<template>
  <div class="chrome" :class="{ hidden: !visible }">
    <button type="button" class="ctrl back" @click="goBack">返回</button>

    <span class="page-label">{{ pageLabel }}</span>

    <input
      class="slider"
      type="range"
      min="0"
      :max="Math.max(state.total - 1, 0)"
      :value="state.index"
      :disabled="state.total === 0"
      aria-label="页码"
      @input="onSlider"
    />

    <div class="group" role="group" aria-label="阅读模式">
      <button
        v-for="opt in MODE_OPTIONS"
        :key="opt.value"
        type="button"
        class="ctrl"
        :class="{ active: state.mode === opt.value }"
        @click="emit('setMode', opt.value)"
      >
        {{ opt.label }}
      </button>
    </div>

    <div class="group" role="group" aria-label="阅读方向">
      <button
        v-for="opt in DIRECTION_OPTIONS"
        :key="opt.value"
        type="button"
        class="ctrl"
        :class="{ active: state.direction === opt.value }"
        @click="emit('setDirection', opt.value)"
      >
        {{ opt.label }}
      </button>
    </div>

    <button type="button" class="ctrl favorite" :disabled="favoriteBusy" @click="toggleFavorite">
      {{ favorited ? '取消收藏' : '收藏' }}
    </button>

    <span class="spacer" />

    <span class="esc-hint">按 Esc 退出</span>
  </div>
</template>

<style scoped>
.chrome {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-4);
  padding: var(--space-3) var(--space-5);
  background: color-mix(in srgb, var(--ground) 78%, transparent);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  color: var(--text);
  transition: opacity var(--dur-base) var(--ease);
}

.chrome.hidden {
  opacity: 0;
  pointer-events: none;
}

/* 焦点在 chrome 内部时永远不淡出——否则键盘用户翻到一半就再也够不着控制条了。 */
.chrome.hidden:focus-within {
  opacity: 1;
  pointer-events: auto;
}

.page-label {
  font-family: var(--font-data);
  font-variant-numeric: tabular-nums;
  font-size: var(--step--1);
  white-space: nowrap;
}

.slider {
  flex: 1 1 160px;
  min-width: 120px;
  max-width: 360px;
  accent-color: var(--accent);
}

.group {
  display: flex;
  gap: var(--space-1);
  padding: 2px;
  border-radius: var(--radius);
  background: rgb(0 0 0 / 0.25);
}

.ctrl {
  padding: var(--space-1) var(--space-3);
  border: 1px solid transparent;
  border-radius: var(--radius);
  background: transparent;
  color: var(--text);
  font-family: var(--font-body);
  font-size: var(--step--1);
  font-weight: 600;
  white-space: nowrap;
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease);
}

.ctrl:hover:not(:disabled) {
  border-color: var(--accent);
}

.ctrl:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.ctrl.active {
  background: var(--accent-dim);
  color: var(--accent);
}

.ctrl.back {
  background: rgb(255 255 255 / 0.08);
}

.spacer {
  flex: 1;
}

.esc-hint {
  font-size: var(--step--1);
  color: var(--dim);
  white-space: nowrap;
}

@media (max-width: 720px) {
  .esc-hint {
    display: none;
  }
}
</style>
