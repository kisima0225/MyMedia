<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { listTags, itemsOfTag } from '@/api/tags'
import type { TagSummary, TaggedTarget } from '@/api/types'
import VideoCard from '@/components/video/VideoCard.vue'
import NodeGrid from '@/components/image/NodeGrid.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import ErrorState from '@/components/common/ErrorState.vue'

// props: true（router/index.ts）把 :id 注入成字符串——与 NodeBrowseView 同一条规矩。
const props = defineProps<{ id: string }>()
const tagId = computed(() => Number(props.id))

const status = ref<'loading' | 'ready' | 'error'>('loading')
const error = ref<unknown>(null)
// "标签不存在"是一个合法的、非网络错误的结果（id 对应的标签已被删除/链接有误）——
// 单独开一个字段，不复用 status === 'error' 那条网络错误分支。
const notFound = ref(false)
const videoTags = ref<TagSummary[]>([])
const imageTags = ref<TagSummary[]>([])
const currentTag = ref<TagSummary | null>(null)
const videoItems = ref<TaggedTarget[]>([])
const imageItems = ref<TaggedTarget[]>([])

async function load(): Promise<void> {
  status.value = 'loading'
  error.value = null
  notFound.value = false
  currentTag.value = null
  videoItems.value = []
  imageItems.value = []
  try {
    // TagController 没有单标签查询端点（只有 list(domain)/create/delete）——
    // 两次全量拉取两个域各自的标签词表（都很短，不分页），顺带解出当前标签属于
    // 哪个域、以及它的显示名，不需要额外请求。视频标签与图片标签分开列，
    // 不放在同一排里：它们在后端就是按 domain 隔离的两套。
    const [video, image] = await Promise.all([listTags('VIDEO'), listTags('IMAGE')])
    videoTags.value = video
    imageTags.value = image

    const found = video.find((tag) => tag.id === tagId.value) ?? image.find((tag) => tag.id === tagId.value)
    if (!found) {
      notFound.value = true
      status.value = 'ready'
      return
    }
    currentTag.value = found

    const items = await itemsOfTag(tagId.value)
    if (found.domain === 'VIDEO') {
      videoItems.value = items
    } else {
      imageItems.value = items
    }
    status.value = 'ready'
  } catch (err) {
    error.value = err
    status.value = 'error'
  }
}

onMounted(load)
watch(tagId, load)

// TaggedTarget(id, title, coverAssetId) 结构上已经满足 VideoCard 的局部 VideoCardItem
// 类型，视频域标签结果不需要映射，直接传。

// 图片域需要映射进 BookCard 的局部类型：TaggedTarget 没有 readable/browsable
// （后端注释："只需要够画一张卡片"）——两者都填 false，BookCard 的 target 计算属性
// 在两者皆假时总落到浏览页（image-node）分支，对"这到底是本书还是文件夹"未知的情况
// 永远安全；真实的 readable/browsable 会在 NodeBrowseView 用 nodeDetail(id) 重新查出来
// （D 段 R17）。totalPageCount/childNodeCount 填 0——两个标志都假时 BookCard 不渲染
// 这两个数字，不会显示编造的"0 页/0 项"。
const imageCards = computed(() =>
  imageItems.value.map((item) => ({
    id: item.id,
    displayName: item.title,
    coverAssetId: item.coverAssetId,
    readable: false,
    browsable: false,
    totalPageCount: 0,
    childNodeCount: 0,
  })))
</script>

<template>
  <div class="tag-view">
    <div v-if="status === 'loading'" class="skeleton-page">
      <div class="skeleton-chip-row">
        <div v-for="n in 6" :key="n" class="skeleton-chip" />
      </div>
      <div class="skeleton-grid">
        <div v-for="n in 6" :key="n" class="skeleton-card" />
      </div>
    </div>

    <ErrorState v-else-if="status === 'error'" :error="error" :onRetry="load" />

    <div v-else-if="notFound" class="not-found" role="alert">
      <p class="title">标签不存在</p>
      <p class="hint">这个标签可能已被删除，或链接有误。</p>
    </div>

    <template v-else>
      <h1 v-if="currentTag" class="page-title">#{{ currentTag.name }}</h1>

      <!-- 两个域各自的分区始终渲染 chip 行（供切换）；只有当前标签所属的那个分区
           在 chip 行下方渲染结果——另一域只显示 chip 行。 -->
      <section data-domain="video" class="domain-section">
        <h2 class="section-heading">
          <span>视频标签</span>
          <span class="rule" aria-hidden="true" />
        </h2>
        <div class="chip-row">
          <RouterLink
            v-for="tag in videoTags"
            :key="tag.id"
            :to="{ name: 'tag', params: { id: tag.id } }"
            class="chip"
            :class="{ current: tag.id === tagId }"
          >
            {{ tag.name }}
          </RouterLink>
        </div>

        <template v-if="currentTag?.domain === 'VIDEO'">
          <EmptyState v-if="videoItems.length === 0" title="这个标签下还没有视频" />
          <div v-else class="grid">
            <VideoCard v-for="item in videoItems" :key="item.id" :item="item" />
          </div>
        </template>
      </section>

      <section data-domain="image" class="domain-section">
        <h2 class="section-heading">
          <span>图片标签</span>
          <span class="rule" aria-hidden="true" />
        </h2>
        <div class="chip-row">
          <RouterLink
            v-for="tag in imageTags"
            :key="tag.id"
            :to="{ name: 'tag', params: { id: tag.id } }"
            class="chip"
            :class="{ current: tag.id === tagId }"
          >
            {{ tag.name }}
          </RouterLink>
        </div>

        <template v-if="currentTag?.domain === 'IMAGE'">
          <EmptyState v-if="imageCards.length === 0" title="这个标签下还没有图片" />
          <NodeGrid v-else :nodes="imageCards" />
        </template>
      </section>
    </template>
  </div>
</template>

<style scoped>
.page-title {
  margin-bottom: var(--space-5);
  font-family: var(--display);
  font-weight: 600;
  font-size: var(--step-2);
  color: var(--text);
}

.domain-section + .domain-section {
  margin-top: var(--space-6);
}

.section-heading {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
  font-family: var(--display);
  font-weight: 600;
  font-size: var(--step-1);
  color: var(--text);
}

.rule {
  flex: 1;
  height: 1px;
  background: var(--line);
}

.chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-bottom: var(--space-4);
}

.chip {
  padding: var(--space-1) var(--space-3);
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--raised);
  color: var(--dim);
  font-size: var(--step--1);
  text-decoration: none;
  transition: border-color var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}

.chip:hover,
.chip:focus-visible {
  border-color: var(--accent);
  color: var(--text);
}

.chip.current {
  border-color: var(--accent);
  background: var(--accent-dim);
  color: var(--accent);
  font-weight: 600;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: var(--space-5) var(--space-4);
}

.not-found {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-7) var(--space-5);
  text-align: center;
}

.not-found .title {
  font-size: var(--step-1);
  color: var(--text);
}

.not-found .hint {
  font-size: var(--step-0);
  color: var(--dim);
  max-width: 40ch;
}

.skeleton-page {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

.skeleton-chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.skeleton-chip {
  width: 4.5em;
  height: 1.8em;
  border-radius: 999px;
  background: var(--raised);
  animation: skeleton-breathe 1.4s ease-in-out infinite;
}

.skeleton-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: var(--space-5) var(--space-4);
}

.skeleton-card {
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
