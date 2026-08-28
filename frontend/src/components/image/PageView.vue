<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { mediaUrl } from '@/api/media'

const props = defineProps<{
  fileId: number
  alt: string
  /** 当前正在阅读的这一页（或双页展开的这一对）为真；决定 loading/fetchpriority。 */
  eager: boolean
  /** 后端 PageSummary 给的原始尺寸，只用来算占位块的长宽比——不参与实际渲染。 */
  width: number | null
  height: number | null
  /**
   * 提供时直接当 src 用，跳过内部的 mediaUrl() 调用——分享页（ShareView）用它
   * 传一个不带票据的公开地址（分享访客没有认证域的媒体票据）。不提供时
   * 保持现状，走认证域的 mediaUrl()。纯加法，不影响既有调用方。
   */
  srcOverride?: string
}>()

const src = ref('')
const loaded = ref(false)

// 与 PlayerView/mediaUrl 同一条规矩：先拿票据再赋 src，顺序反了 <img> 会先发
// 一次无票据请求吃 401，某些浏览器会把这个 src 标记为失败、不再重试。
async function load(): Promise<void> {
  loaded.value = false
  src.value = ''
  src.value = props.srcOverride ?? await mediaUrl(`/api/image/page/${props.fileId}`)
}

onMounted(load)
watch(() => props.fileId, load)

function onLoad(): void {
  loaded.value = true
}

// aspect-ratio 只是给占位块一个大致的长宽比，不追求跟最终 contain 后的
// 像素尺寸完全一致——占位块的职责是「别让翻页时版面跳一下」，不是精确预演。
const ratio = computed(() =>
  props.width && props.height ? props.width / props.height : null)
</script>

<template>
  <div class="page-view">
    <div
      v-if="!loaded"
      class="placeholder"
      :style="ratio ? { aspectRatio: String(ratio) } : {}"
      aria-hidden="true"
    />
    <img
      v-if="src"
      :src="src"
      :alt="alt"
      :loading="eager ? 'eager' : 'lazy'"
      :fetchpriority="eager ? 'high' : 'auto'"
      class="page-img"
      :class="{ hidden: !loaded }"
      @load="onLoad"
    />
  </div>
</template>

<style scoped>
.page-view {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  max-height: 100vh;
}

.placeholder {
  width: 100%;
  min-height: 30vh;
  max-height: 100vh;
  background: var(--page);
}

.page-img {
  display: block;
  max-height: 100vh;
  max-width: 100%;
  /* 绝不裁切：漫画页少一条边就少一格分镜 */
  object-fit: contain;
}

.page-img.hidden {
  /* loaded 变 true 之前不占版面——占位块顶着，避免图片解码完成的一瞬间版面再跳一次 */
  position: absolute;
  opacity: 0;
  pointer-events: none;
}
</style>
