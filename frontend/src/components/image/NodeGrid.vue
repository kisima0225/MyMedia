<script setup lang="ts">
import BookCard from './BookCard.vue'

// 局部类型，与 BookCard 的 node prop 同形——NodeGrid 只是把数组摊开逐个转交，
// 不引入对 ImageNodeSummary 的硬依赖，往后换一种更窄的命中形状也能直接喂进来。
interface GridNode {
  id: number
  displayName: string
  coverAssetId: number | null
  readable: boolean
  browsable: boolean
  totalPageCount: number
  childNodeCount: number
}

defineProps<{ nodes: GridNode[] }>()
</script>

<template>
  <div class="grid">
    <BookCard v-for="node in nodes" :key="node.id" :node="node" />
  </div>
</template>

<style scoped>
/*
 * 为什么用 CSS 多列而不是 JS 瀑布流库：CSS 多列的排布顺序是"竖着走"
 * （1 2 3 在第一列），与"横着走"的阅读直觉不同。对一个按 sort_key 排序的
 * 媒体库网格，这个差别可以接受——用户是在扫视找封面，不是在按顺序读。
 * 换来的是零依赖、零布局抖动、天然响应式。
 *
 * 为什么图片域用瀑布流而视频域用等高网格：视频封面天然是 16:9，裁成统一比例
 * 不损失信息；而漫画单行本、同人本、表情包截图的比例天差地别，强行裁成 2:3
 * 会把封面的标题和角色脸切掉。
 */
.grid {
  columns: 5 200px;
  column-gap: var(--space-4);
}

.grid > * {
  /* 没有它，一张卡会被拆到两列之间 */
  break-inside: avoid;
  margin-bottom: var(--space-5);
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
</style>
