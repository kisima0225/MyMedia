<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { nodeDetail, browse, setReadingMode } from '@/api/image'
import type { ImageNodeSummary } from '@/api/types'
import Breadcrumb from '@/components/image/Breadcrumb.vue'
import NodeGrid from '@/components/image/NodeGrid.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import ErrorState from '@/components/common/ErrorState.vue'

// props: true（router/index.ts）把 :id 注入成字符串——路由参数永远是字符串，
// 转数字的责任留在这个视图里，与 PlayerView 的 fileId 同一条规矩。
const props = defineProps<{ id: string }>()
const nodeId = computed(() => Number(props.id))

const status = ref<'loading' | 'ready' | 'error'>('loading')
const error = ref<unknown>(null)
const node = ref<ImageNodeSummary | null>(null)
const browseData = ref<{ breadcrumb: ImageNodeSummary[]; nodes: ImageNodeSummary[] } | null>(null)
// 阅读模式切换失败时的可见反馈——error 只在 status === 'error'（整页错误态）时
// 才会被 ErrorState 读到，切换失败不该把整页判成 error，所以单独开一个字段。
const modeError = ref<string | null>(null)

const READING_MODE_OPTIONS: { value: ImageNodeSummary['readingMode']; label: string }[] = [
  { value: 'AUTO', label: '自动判定' },
  { value: 'FORCE_BOOK', label: '当作一本书' },
  { value: 'FORCE_FOLDER', label: '当作文件夹' },
]

// 路由只给 id，不给 libraryId，但 browse() 必须带 libraryId——所以顺序固定为
// 先查这个节点自己（拿到它的 libraryId、readable/browsable、directPageCount 等），
// 再用查到的 libraryId 去查 breadcrumb + 子节点。两次请求有依赖，不能 Promise.all。
async function load(): Promise<void> {
  status.value = 'loading'
  error.value = null
  node.value = null
  browseData.value = null
  try {
    const detail = await nodeDetail(nodeId.value)
    node.value = detail
    browseData.value = await browse(detail.libraryId, nodeId.value)
    status.value = 'ready'
  } catch (err) {
    error.value = err
    status.value = 'error'
  }
}

onMounted(load)
watch(nodeId, load)

// 切换阅读模式只影响当前节点自己的 readable/browsable（子节点树结构不受影响），
// 用返回的最新节点直接替换本地状态即可——两个区块的显隐是从 node.readable /
// node.browsable 算出来的计算属性，会跟着自动更新，不用手动重新拉子节点列表。
async function onReadingModeChange(event: Event): Promise<void> {
  const current = node.value
  if (!current) return
  const select = event.target as HTMLSelectElement
  const mode = select.value as ImageNodeSummary['readingMode']
  if (mode === current.readingMode) return
  modeError.value = null
  try {
    node.value = await setReadingMode(current.id, mode)
  } catch (err) {
    // 浏览器在 @change 触发之前就已经把 <select> 的显示值改成了用户点的那项；
    // 这里的 :value 绑定表达式（node.readingMode）在失败时没有变化，Vue 检测不到
    // "需要把 DOM 写回去"的理由，不会自动纠正。所以要拿着这次事件的 event.target
    // 手动把显示值拨回真正生效的模式，否则下拉框会静默停在一个从未生效的选项上，
    // 且没有任何界面提示——console.warn 用户看不到。
    select.value = current.readingMode
    modeError.value = '切换阅读模式失败，请重试'
    console.warn('切换阅读模式失败', err)
  }
}
</script>

<template>
  <div class="node-browse">
    <div v-if="status === 'loading'" class="skeleton-wrap">
      <div class="skeleton-crumb" />
      <div class="skeleton-banner" />
      <div class="skeleton-grid">
        <div v-for="n in 8" :key="n" class="skeleton-card" />
      </div>
    </div>

    <ErrorState v-else-if="status === 'error'" :error="error" :onRetry="load" />

    <template v-else-if="node && browseData">
      <div class="header-row">
        <Breadcrumb :trail="browseData.breadcrumb" />
        <div class="mode-switch-wrap">
          <label class="mode-switch">
            <span>阅读模式</span>
            <select :value="node.readingMode" @change="onReadingModeChange">
              <option v-for="opt in READING_MODE_OPTIONS" :key="opt.value" :value="opt.value">
                {{ node.readingMode === opt.value ? '✓ ' : '' }}{{ opt.label }}
              </option>
            </select>
          </label>
          <span v-if="modeError" class="mode-error" role="alert">{{ modeError }}</span>
        </div>
      </div>

      <!-- 双入口是这个页面的全部意义（spec §6.4）：readable 与 browsable 同时为真时，
           两个区块都渲染。一个既装着散图又装着子目录的资料夹是图片库里的常态
           （Perfect Viewer 的行为，后端 image_node 就是照着它建模的），不是边界情况。 -->
      <div v-if="node.readable" class="banner">
        <span class="banner-text">这个目录里有 {{ node.directPageCount }} 张散图</span>
        <RouterLink :to="{ name: 'image-read', params: { id: node.id } }" class="banner-action">
          开始阅读
        </RouterLink>
      </div>

      <template v-if="node.browsable">
        <EmptyState v-if="browseData.nodes.length === 0" title="这个目录下没有子项" />
        <NodeGrid v-else :nodes="browseData.nodes" />
      </template>

      <EmptyState
        v-if="!node.readable && !node.browsable"
        title="这个节点没有可显示的内容"
        hint="试试切换上方的阅读模式"
      />
    </template>
  </div>
</template>

<style scoped>
.header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  margin-bottom: var(--space-5);
}

.mode-switch-wrap {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: var(--space-1);
  flex: none;
}

.mode-switch {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  flex: none;
  font-size: var(--step--1);
  color: var(--dim);
}

.mode-error {
  font-size: var(--step--1);
  color: var(--accent);
}

.mode-switch select {
  padding: var(--space-1) var(--space-2);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--raised);
  color: var(--text);
  font-family: var(--font-body);
  font-size: var(--step-0);
}

.banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  margin-bottom: var(--space-5);
  padding: var(--space-4) var(--space-5);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--raised);
}

.banner-text {
  font-size: var(--step-0);
  color: var(--text);
}

.banner-action {
  flex: none;
  padding: var(--space-2) var(--space-4);
  border: none;
  border-radius: var(--radius);
  background: var(--accent-dim);
  color: var(--accent);
  font-family: var(--font-body);
  font-size: var(--step-0);
  font-weight: 600;
  text-decoration: none;
  transition: filter var(--dur-fast) var(--ease);
}

.banner-action:hover {
  filter: brightness(1.2);
}

.skeleton-wrap {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

.skeleton-crumb {
  width: 240px;
  height: 18px;
  border-radius: var(--radius);
  background: var(--raised);
  animation: skeleton-breathe 1.4s ease-in-out infinite;
}

.skeleton-banner {
  height: 56px;
  border-radius: var(--radius);
  background: var(--raised);
  animation: skeleton-breathe 1.4s ease-in-out infinite;
}

.skeleton-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: var(--space-5) var(--space-4);
}

.skeleton-card {
  aspect-ratio: 2 / 3;
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
