<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { browse } from '@/api/video'
import { useLibrariesStore } from '@/stores/libraries'
import { useAuthStore } from '@/stores/auth'
import type { VideoBrowseResult } from '@/api/types'
import VideoCard from '@/components/video/VideoCard.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import ErrorState from '@/components/common/ErrorState.vue'

// 目录树是次要视图（spec §6.3）：只承载导航，不显示元数据与进度。
// 条目节点没有 coverAssetId，直接传给 <VideoCard> 时靠结构类型系统对上——
// Cover.vue 渲染标题首字占位，这是预期效果，不是缺数据的 bug。

const route = useRoute()
const librariesStore = useLibrariesStore()
const auth = useAuthStore()

const status = ref<'loading' | 'ready' | 'error'>('loading')
const error = ref<unknown>(null)
const data = ref<VideoBrowseResult | null>(null)

// 目录导航是纯前端状态，不进 URL query——这是次要视图，不需要面包屑深链。
// libraryId 的初始值才需要读 query（见下），那是入口链接（首页/其他视图）传进来的。
const selectedLibraryId = ref<number | undefined>(undefined)
const currentFolderId = ref<number | undefined>(undefined)

function parseLibraryIdFromQuery(): number | undefined {
  const raw = route.query.libraryId
  if (typeof raw !== 'string') return undefined
  const n = Number(raw)
  if (!Number.isFinite(n)) return undefined
  // 不仅要求能解析成数字，还要求它确实是当前用户能看到的一个视频库——
  // 防止一个指向图片库、或已被删除/无权限的库的 id 静默地把浏览页带进死路。
  return librariesStore.videoLibraries.some((lib) => lib.id === n) ? n : undefined
}

async function loadBrowse(): Promise<void> {
  if (selectedLibraryId.value == null) return
  status.value = 'loading'
  error.value = null
  try {
    data.value = await browse(selectedLibraryId.value, currentFolderId.value)
    status.value = 'ready'
  } catch (err) {
    error.value = err
    status.value = 'error'
  }
}

async function init(): Promise<void> {
  status.value = 'loading'
  error.value = null
  try {
    // VideoHomeView 自己的 onMounted 里也调了一次 load()——不能假设用户一定是从首页
    // 点进来的（直接输网址、刷新、书签都可能），这里独立兜底。库已经加载过时这是一次
    // 多余的网络请求，成本很低，不值得为它加一层"是否已加载"的判断。
    await librariesStore.load()
    selectedLibraryId.value = parseLibraryIdFromQuery() ?? librariesStore.videoLibraries[0]?.id
    currentFolderId.value = undefined
    if (selectedLibraryId.value == null) {
      // 零个视频库：没有可浏览的东西，渲染空态，不拿 undefined 去调接口。
      status.value = 'ready'
      return
    }
    await loadBrowse()
  } catch (err) {
    error.value = err
    status.value = 'error'
  }
}

onMounted(init)

function onLibraryChange(event: Event): void {
  const id = Number((event.target as HTMLSelectElement).value)
  if (!Number.isFinite(id)) return
  selectedLibraryId.value = id
  currentFolderId.value = undefined
  void loadBrowse()
}

function goToFolder(id: number | undefined): void {
  currentFolderId.value = id
  void loadBrowse()
}

// 面包屑：后端只在有 folderId 时才返回祖先链（且含当前目录自己，见 breadcrumb()
// 对物化路径的解析），顶层时是空数组。前面补一段代表"媒体库根"的虚拟节点，
// 让面包屑在任何深度下都至少有一段可显示。
const crumbs = computed(() => {
  const rootLabel =
    librariesStore.videoLibraries.find((lib) => lib.id === selectedLibraryId.value)?.name ?? '根目录'
  const root = { id: undefined as number | undefined, name: rootLabel }
  return [root, ...(data.value?.breadcrumb ?? [])]
})
</script>

<template>
  <div class="video-browse">
    <template v-if="selectedLibraryId == null">
      <div class="empty-wrap">
        <EmptyState title="没有可浏览的视频库" hint="去『媒体库管理』创建一个视频库" />
        <RouterLink v-if="auth.isAdmin" :to="{ name: 'admin-libraries' }" class="jump">
          去媒体库管理
        </RouterLink>
      </div>
    </template>

    <template v-else>
      <div v-if="librariesStore.videoLibraries.length > 1" class="library-switch">
        <label>
          <span>媒体库</span>
          <select :value="selectedLibraryId" @change="onLibraryChange">
            <option v-for="lib in librariesStore.videoLibraries" :key="lib.id" :value="lib.id">
              {{ lib.name }}
            </option>
          </select>
        </label>
      </div>

      <nav class="breadcrumb" aria-label="目录路径">
        <template v-for="(crumb, index) in crumbs" :key="crumb.id ?? 'root'">
          <button
            v-if="index < crumbs.length - 1"
            type="button"
            class="crumb-link"
            @click="goToFolder(crumb.id)"
          >
            {{ crumb.name }}
          </button>
          <span v-else class="crumb-current">{{ crumb.name }}</span>
          <span v-if="index < crumbs.length - 1" class="sep" aria-hidden="true">&gt;</span>
        </template>
      </nav>

      <div v-if="status === 'loading'" class="skeleton-wrap">
        <div v-for="n in 4" :key="n" class="skeleton-row" />
      </div>

      <ErrorState v-else-if="status === 'error'" :error="error" :onRetry="loadBrowse" />

      <template v-else-if="data">
        <EmptyState v-if="data.folders.length === 0 && data.items.length === 0" title="这个目录是空的" />

        <ul v-if="data.folders.length" class="folder-list">
          <li v-for="folder in data.folders" :key="folder.id">
            <button type="button" class="folder-row" @click="goToFolder(folder.id)">
              <svg
                class="folder-icon"
                viewBox="0 0 20 20"
                width="18"
                height="18"
                aria-hidden="true"
              >
                <path
                  fill="currentColor"
                  d="M2 4.5A1.5 1.5 0 0 1 3.5 3h4.086a1.5 1.5 0 0 1 1.06.44L9.5 4.5H16.5A1.5 1.5 0 0 1 18 6v9.5A1.5 1.5 0 0 1 16.5 17h-13A1.5 1.5 0 0 1 2 15.5v-11Z"
                />
              </svg>
              <span class="folder-name">{{ folder.name }}</span>
              <span class="folder-count">{{ folder.totalItemCount }}</span>
            </button>
          </li>
        </ul>

        <div v-if="data.items.length" class="grid">
          <VideoCard v-for="item in data.items" :key="item.id" :item="item" />
        </div>
      </template>
    </template>
  </div>
</template>

<style scoped>
.empty-wrap {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-3);
}

.jump {
  padding: var(--space-2) var(--space-4);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--raised);
  color: var(--text);
  font-family: var(--font-body);
  font-size: var(--step-0);
  font-weight: 600;
  text-decoration: none;
  transition: border-color var(--dur-fast) var(--ease);
}

.jump:hover {
  border-color: var(--accent);
}

.library-switch {
  margin-bottom: var(--space-4);
}

.library-switch label {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--step--1);
  color: var(--dim);
}

.library-switch select {
  padding: var(--space-1) var(--space-2);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--raised);
  color: var(--text);
  font-family: var(--font-body);
  font-size: var(--step-0);
}

.breadcrumb {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-5);
  font-size: var(--step-0);
}

.crumb-link {
  padding: 0;
  border: none;
  background: none;
  color: var(--dim);
  font-family: var(--font-body);
  font-size: inherit;
  cursor: pointer;
  transition: color var(--dur-fast) var(--ease);
}

.crumb-link:hover {
  color: var(--accent);
}

.crumb-current {
  color: var(--text);
  font-weight: 600;
}

.sep {
  color: var(--dim);
}

.folder-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  margin-bottom: var(--space-5);
  list-style: none;
}

.folder-row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  width: 100%;
  padding: var(--space-2) var(--space-3);
  border: 1px solid transparent;
  border-radius: var(--radius);
  background: none;
  color: var(--text);
  font-family: var(--font-body);
  font-size: var(--step-0);
  text-align: left;
  cursor: pointer;
  transition: border-color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}

.folder-row:hover,
.folder-row:focus-visible {
  border-color: var(--line);
  background: var(--raised);
}

.folder-icon {
  flex: none;
  color: var(--dim);
}

.folder-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.folder-count {
  flex: none;
  font-family: var(--font-data);
  font-variant-numeric: tabular-nums;
  font-size: var(--step--1);
  color: var(--dim);
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: var(--space-5) var(--space-4);
}

.skeleton-wrap {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.skeleton-row {
  height: 40px;
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
