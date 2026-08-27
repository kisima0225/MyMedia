<script setup lang="ts">
import { computed } from 'vue'
import { assetUrl } from '@/api/media'

const props = defineProps<{
  assetId: number | null | undefined
  ratio: '16/9' | '2/3'
  alt: string
}>()

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
  box-shadow: var(--elevation);
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
