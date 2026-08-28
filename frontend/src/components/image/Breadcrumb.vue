<script setup lang="ts">
import { ref, computed } from 'vue'

// 局部类型而不是直接用 ImageNodeSummary：这里只需要 id 与展示名两个字段，
// 结构类型系统认，调用方（NodeBrowseView）直接把 browse() 返回的 breadcrumb
// 数组传进来即可，不用先裁剪一遍。
interface Crumb {
  id: number
  displayName: string
}

const props = defineProps<{ trail: Crumb[] }>()

// 后端 browse() 的 breadcrumb 已经含当前节点自己（最后一项），前端不用再补一遍
// （见 ImageBrowseServiceTest.breadcrumbResolvesAncestorsWithoutRecursiveQuery：
// 查一个三层深的节点，返回的就是三层名字，含它自己）。

// 超过 4 级时把中间折叠成一个「…」：保留最上层（从哪个大类进来的）与最下面两级
// （父级 + 当前），中间那些既回答不了"从哪来"也回答不了"在哪"的层级先藏起来，
// 点「…」再展开——不是丢弃，只是先不占地方。
const expanded = ref(false)

type Segment = { kind: 'crumb'; crumb: Crumb } | { kind: 'ellipsis' }

const segments = computed<Segment[]>(() => {
  const trail = props.trail
  if (expanded.value || trail.length <= 4) {
    return trail.map((crumb) => ({ kind: 'crumb' as const, crumb }))
  }
  return [
    { kind: 'crumb', crumb: trail[0] },
    { kind: 'ellipsis' },
    { kind: 'crumb', crumb: trail[trail.length - 2] },
    { kind: 'crumb', crumb: trail[trail.length - 1] },
  ]
})

function expand(): void {
  expanded.value = true
}
</script>

<template>
  <nav v-if="trail.length" class="breadcrumb" aria-label="所在位置">
    <template v-for="(segment, index) in segments" :key="segment.kind === 'ellipsis' ? 'ellipsis' : segment.crumb.id">
      <span v-if="index > 0" class="sep" aria-hidden="true">/</span>
      <button v-if="segment.kind === 'ellipsis'" type="button" class="ellipsis" @click="expand">…</button>
      <span v-else-if="index === segments.length - 1" class="current">{{ segment.crumb.displayName }}</span>
      <RouterLink
        v-else
        :to="{ name: 'image-node', params: { id: segment.crumb.id } }"
        class="crumb-link"
      >
        {{ segment.crumb.displayName }}
      </RouterLink>
    </template>
  </nav>
</template>

<style scoped>
.breadcrumb {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--space-2);
  font-family: var(--display);
  font-size: var(--step--1);
}

.crumb-link {
  color: var(--dim);
  text-decoration: none;
  transition: color var(--dur-fast) var(--ease);
}

.crumb-link:hover {
  color: var(--accent);
}

.current {
  color: var(--text);
  font-weight: 600;
}

.ellipsis {
  padding: 0;
  border: none;
  background: none;
  color: var(--dim);
  font-family: inherit;
  font-size: inherit;
  cursor: pointer;
  transition: color var(--dur-fast) var(--ease);
}

.ellipsis:hover {
  color: var(--accent);
}

.sep {
  color: var(--dim);
}
</style>
