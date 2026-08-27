<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { itemDetail, continueWatching } from '@/api/video'
import { favorite, unfavorite, listFavorites } from '@/api/favorites'
import { createShare } from '@/api/shares'
import { useAuthStore } from '@/stores/auth'
import Cover from '@/components/common/Cover.vue'
import ErrorState from '@/components/common/ErrorState.vue'
import TagPicker from '@/components/common/TagPicker.vue'
import EpisodeList from '@/components/video/EpisodeList.vue'
import type { VideoItemSummary, VideoGroupSummary, VideoFileSummary, ContinueWatchingEntry } from '@/api/types'

// props: true（router/index.ts）把 :id 直接注入成这个字符串 prop——路由参数永远是字符串，
// 转成数字的责任留在这个视图里，不往上游泄露。
const props = defineProps<{ id: string }>()
const itemId = computed(() => Number(props.id))

const authStore = useAuthStore()

interface Detail {
  item: VideoItemSummary
  groups: VideoGroupSummary[]
  files: VideoFileSummary[]
}

const status = ref<'loading' | 'ready' | 'error'>('loading')
const error = ref<unknown>(null)
const detail = ref<Detail | null>(null)
const progressEntries = ref<ContinueWatchingEntry[]>([])

const favorited = ref(false)
const favoriteBusy = ref(false)
const favoriteErrorText = ref<string | null>(null)

async function load(): Promise<void> {
  status.value = 'loading'
  error.value = null
  favoriteErrorText.value = null
  try {
    const id = itemId.value
    const [detailResult, continueResult, favoritesResult] = await Promise.all([
      itemDetail(id),
      continueWatching(),
      listFavorites('video'),
    ])
    detail.value = detailResult
    // continueWatching() 是全局的（跨条目）——按 itemId 过滤出属于这个条目的那些，
    // 一次请求同时喂给"播放"按钮的续播判断和 EpisodeList 的逐行进度。
    progressEntries.value = continueResult.filter((entry) => entry.itemId === id)
    favorited.value = favoritesResult.some((entry) => entry.id === id)
    status.value = 'ready'
  } catch (err) {
    error.value = err
    status.value = 'error'
  }
}

onMounted(load)
watch(() => props.id, load)

const ITEM_TYPE_LABEL: Record<VideoItemSummary['itemType'], string> = {
  MOVIE: '电影',
  SERIES: '剧集',
  SINGLE_VIDEO: '单集视频',
  VIDEO_SERIES: '视频合集',
}

const STRUCTURE_LABEL: Record<VideoItemSummary['structure'], string> = {
  FLAT: '平铺',
  GROUPED: '分组',
}

/**
 * "播放"这个动作对用户的隐含承诺是"接着看"——本条目下若存在未看完的进度，
 * 直接续播那一集；否则退到剧集列表里排序最靠前的正片/版本文件。
 */
const primaryPlayTarget = computed<{ fileId: number; position: number | null } | null>(() => {
  if (!detail.value) return null
  const inProgress = progressEntries.value.find((entry) => !entry.completed)
  if (inProgress) {
    return { fileId: inProgress.fileId, position: inProgress.positionSeconds }
  }
  const files = detail.value.files
  const playable = files.filter((file) => file.role === 'PRIMARY' || file.role === 'VERSION')
  const pool = playable.length > 0 ? playable : files
  const sorted = [...pool].sort((a, b) => (a.episodeIndex ?? Infinity) - (b.episodeIndex ?? Infinity))
  const first = sorted[0]
  return first ? { fileId: first.id, position: null } : null
})

async function toggleFavorite(): Promise<void> {
  if (!detail.value || favoriteBusy.value) return
  favoriteBusy.value = true
  favoriteErrorText.value = null
  try {
    if (favorited.value) {
      await unfavorite('video', detail.value.item.id)
      favorited.value = false
    } else {
      await favorite('video', detail.value.item.id)
      favorited.value = true
    }
  } catch (err) {
    favoriteErrorText.value = err instanceof Error ? err.message : '操作失败，请重试'
  } finally {
    favoriteBusy.value = false
  }
}

const shareOpen = ref(false)
const sharePassword = ref('')
const shareExpiresInDaysInput = ref('')
const shareBusy = ref(false)
const shareErrorText = ref<string | null>(null)
const shareLink = ref<string | null>(null)
const shareCreated = ref(false)
const copyStatus = ref<'idle' | 'copied' | 'failed'>('idle')

const COPY_STATUS_LABEL: Record<typeof copyStatus.value, string> = {
  idle: '复制链接',
  copied: '已复制',
  failed: '复制失败',
}
const copyButtonLabel = computed(() => COPY_STATUS_LABEL[copyStatus.value])

function toggleSharePanel(): void {
  shareOpen.value = !shareOpen.value
}

async function submitShare(): Promise<void> {
  if (!detail.value || shareBusy.value) return
  shareBusy.value = true
  shareErrorText.value = null
  try {
    const body: { password?: string; expiresInDays?: number } = {}
    const password = sharePassword.value.trim()
    if (password) body.password = password
    const daysText = shareExpiresInDaysInput.value.trim()
    if (daysText) {
      const days = Number(daysText)
      if (Number.isFinite(days)) body.expiresInDays = days
    }
    const response = await createShare('video', detail.value.item.id, body)
    shareLink.value = `${location.origin}/s/${response.token}`
    shareCreated.value = true
    copyStatus.value = 'idle'
  } catch (err) {
    shareErrorText.value = err instanceof Error ? err.message : '创建分享链接失败，请重试'
  } finally {
    shareBusy.value = false
  }
}

async function copyShareLink(): Promise<void> {
  if (!shareLink.value) return
  try {
    await navigator.clipboard.writeText(shareLink.value)
    copyStatus.value = 'copied'
  } catch {
    copyStatus.value = 'failed'
  }
}
</script>

<template>
  <div class="item-detail">
    <div v-if="status === 'loading'" class="skeleton-banner" />

    <ErrorState v-else-if="status === 'error'" :error="error" :onRetry="load" />

    <template v-else-if="detail">
      <section class="banner">
        <div class="cover-wrap">
          <Cover :assetId="detail.item.coverAssetId" ratio="16/9" :alt="detail.item.title" />
        </div>

        <div class="content">
          <h1 class="title">{{ detail.item.title }}</h1>

          <div class="badges">
            <span class="badge">{{ ITEM_TYPE_LABEL[detail.item.itemType] }}</span>
            <span class="badge">{{ STRUCTURE_LABEL[detail.item.structure] }}</span>
          </div>

          <!-- 简介需要 /api/video/items/{id}/metadata，这个任务的范围明确不包括它
               （见任务交接说明）——这里先占住版面，数据由后面编辑元数据的任务接上。 -->
          <p class="synopsis">暂无简介</p>

          <TagPicker class="tags" domain="VIDEO" :targetId="detail.item.id" kind="video" />

          <div class="actions">
            <RouterLink
              v-if="primaryPlayTarget"
              class="action primary"
              :to="{
                name: 'video-play',
                params: { fileId: primaryPlayTarget.fileId },
                query: primaryPlayTarget.position != null ? { position: primaryPlayTarget.position } : undefined,
              }"
            >
              播放
            </RouterLink>
            <button v-else type="button" class="action primary" disabled>播放</button>

            <button type="button" class="action" :disabled="favoriteBusy" @click="toggleFavorite">
              {{ favorited ? '取消收藏' : '收藏' }}
            </button>

            <button type="button" class="action" @click="toggleSharePanel">分享</button>

            <RouterLink
              v-if="authStore.isAdmin"
              class="action"
              :to="{ name: 'admin-metadata', params: { domain: 'video', id: detail.item.id } }"
            >
              编辑元数据
            </RouterLink>
          </div>
          <p v-if="favoriteErrorText" class="hint error">{{ favoriteErrorText }}</p>

          <div v-if="shareOpen" class="share-panel">
            <template v-if="!shareCreated">
              <label class="field">
                <span>密码（可选）</span>
                <input v-model="sharePassword" type="password" placeholder="留空表示不设密码" />
              </label>
              <label class="field">
                <span>有效天数（可选，1–365）</span>
                <input
                  v-model="shareExpiresInDaysInput"
                  type="number"
                  min="1"
                  max="365"
                  placeholder="留空表示永不过期"
                />
              </label>
              <button type="button" class="action primary" :disabled="shareBusy" @click="submitShare">
                创建分享链接
              </button>
              <p v-if="shareErrorText" class="hint error">{{ shareErrorText }}</p>
            </template>
            <template v-else>
              <p class="hint success">已创建分享链接</p>
              <div class="share-link-row">
                <code class="share-link">{{ shareLink }}</code>
                <button type="button" class="action" @click="copyShareLink">
                  {{ copyButtonLabel }}
                </button>
              </div>
            </template>
          </div>
        </div>
      </section>

      <EpisodeList
        :files="detail.files"
        :groups="detail.groups"
        :itemId="detail.item.id"
        :structure="detail.item.structure"
        :progress="progressEntries"
      />
    </template>
  </div>
</template>

<style scoped>
.banner {
  display: flex;
  gap: var(--space-5);
  margin-bottom: var(--space-6);
}

.cover-wrap {
  flex: 0 0 360px;
  width: 360px;
}

.content {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.title {
  font-family: var(--display);
  font-size: var(--step-3);
  letter-spacing: -0.02em;
  color: var(--text);
}

.badges {
  display: flex;
  gap: var(--space-2);
}

.badge {
  padding: 2px var(--space-2);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  font-size: var(--step--1);
  color: var(--dim);
}

.synopsis {
  color: var(--dim);
  font-size: var(--step-0);
  max-width: 70ch;
}

.tags {
  margin-top: var(--space-1);
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
  margin-top: var(--space-2);
}

.action {
  padding: var(--space-2) var(--space-4);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--raised);
  color: var(--text);
  font-family: var(--font-body);
  font-size: var(--step-0);
  font-weight: 600;
  text-decoration: none;
  cursor: pointer;
  transition: border-color var(--dur-fast) var(--ease);
}

.action:hover:not(:disabled) {
  border-color: var(--accent);
}

.action:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.action.primary {
  border-color: var(--accent);
  background: var(--accent-dim);
  color: var(--accent);
}

.hint {
  font-size: var(--step--1);
  color: var(--dim);
}

.hint.error {
  color: var(--accent);
}

.hint.success {
  color: var(--text);
}

.share-panel {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  max-width: 28em;
  margin-top: var(--space-2);
  padding: var(--space-4);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--raised);
}

.field {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  font-size: var(--step--1);
  color: var(--dim);
}

.field input {
  padding: var(--space-2);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--ground);
  color: var(--text);
  font-family: var(--font-body);
  font-size: var(--step-0);
}

.field input:focus {
  outline: none;
  border-color: var(--accent);
}

.share-link-row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.share-link {
  flex: 1;
  min-width: 0;
  padding: var(--space-2);
  border-radius: var(--radius);
  background: var(--ground);
  font-family: var(--font-data);
  font-size: var(--step--1);
  color: var(--text);
  overflow-x: auto;
  white-space: nowrap;
}

.skeleton-banner {
  height: calc(360px * 9 / 16);
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

@media (max-width: 720px) {
  .banner {
    flex-direction: column;
  }

  .cover-wrap {
    width: 100%;
  }
}
</style>
