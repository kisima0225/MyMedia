<script setup lang="ts">
import { computed } from 'vue'
import { assetUrl } from '@/api/media'

const props = defineProps<{
  assetId: number | null | undefined
  ratio: '16/9' | '2/3'
  alt: string
}>()

// assetUrl() 内部读的是 api/media.ts 里那个响应式的 latestTicket，所以这个
// computed 同时订阅了它：首屏还没票据时返回空串（渲染占位），票据签发或续签
// 之后自动重算成带票据的真实 URL，不需要手动刷新页面。
const src = computed(() => (props.assetId == null ? null : assetUrl(props.assetId)))
</script>

<template>
  <div class="cover" :style="{ aspectRatio: ratio }">
    <img v-if="src" :src="src" :alt="alt" loading="lazy" decoding="async" />
    <!-- 没有封面是常态而不是错误：刚扫描完、还没轮到 PREVIEW_GENERATE 的条目
         就长这样。所以占位要安静——用标题首字，不用破图图标。 -->
    <span v-else class="placeholder" aria-hidden="true">{{ alt.slice(0, 1) }}</span>
  </div>
</template>

<style scoped>
.cover {
  position: relative;
  overflow: hidden;
  border-radius: var(--radius);
  background: var(--raised);
  /* --elevation 只在两个域下定义（tokens.css）。<Cover> 在中性页面（/search、
     /favorites、/tags/:id）也会出现，那里没有 data-domain，不给兜底值这条声明
     会静默失效、封面失去所有立体感。兜底值与 AppShell 顶栏用的同一个。 */
  box-shadow: var(--elevation, 0 8px 24px -6px rgb(0 0 0 / 0.5));
}

.cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  font-family: var(--display);
  font-weight: 600;
  font-size: var(--step-3);
  color: var(--dim);
  background: var(--raised);
  user-select: none;
}
</style>
