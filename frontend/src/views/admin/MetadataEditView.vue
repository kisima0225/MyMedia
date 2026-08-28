<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ApiError } from '@/api/client'
import { videoMetadata, editVideoMetadata, imageMetadata, editImageMetadata } from '@/api/admin'
import type { MetadataSnapshot } from '@/api/types'
import ErrorState from '@/components/common/ErrorState.vue'

const props = defineProps<{ domain: string; id: string }>()

const domainUpper = computed(() => props.domain.toUpperCase())
const itemId = computed(() => Number(props.id))

/**
 * 标准字段全集，镜像后端 shared.MetadataFields.STANDARD（title/originalTitle/
 * summary/releaseDate/rating）——这是唯一会出现在 MetadataDto.Response.fields 里
 * 的键；非标准字段进 metadata jsonb，不会被 snapshot() 读回来，这里也就没有
 * 对应的编辑行（已读 VideoMetadataStore.snapshot() 源码核对）。
 */
const STANDARD_FIELDS: { key: string; label: string; multiline?: boolean }[] = [
  { key: 'title', label: '标题' },
  { key: 'originalTitle', label: '原始标题' },
  { key: 'summary', label: '简介', multiline: true },
  { key: 'releaseDate', label: '发行日期（YYYY-MM-DD）' },
  { key: 'rating', label: '评分' },
]

/**
 * fieldSources / scrapeSource 的真实取值是这五个精确字符串之一（大小写敏感，
 * preflight 裁决 R35，已读四个 MetadataProvider 的 NAME 常量与两处 applyUserEdit
 * 写入代码核对）。查不到时原样显示，不静默吞掉——那样至少能看出是没见过的来源，
 * 而不是误认成"没有来源"。
 */
const SOURCE_LABEL: Record<string, string> = {
  LocalNfo: 'NFO',
  Bangumi: 'Bangumi',
  TMDB: 'TMDB',
  Filename: '文件名',
  USER: '手动',
}

function sourceLabelOf(source: string | null | undefined): string {
  if (!source) return '—'
  return SOURCE_LABEL[source] ?? source
}

const status = ref<'loading' | 'ready' | 'error'>('loading')
const error = ref<unknown>(null)
const snapshot = ref<MetadataSnapshot | null>(null)

/** 当前编辑框里的值，与 originalValues 的差集就是这次要提交的字段。 */
const editableValues = reactive<Record<string, string>>({})
/** 上一次从服务端加载/保存成功后的基线值，用来判断哪些字段被用户真正改过。 */
const originalValues = reactive<Record<string, string>>({})

const saving = ref(false)
const saveError = ref<string | null>(null)
const saveSuccess = ref(false)

function applySnapshot(data: MetadataSnapshot): void {
  snapshot.value = data
  for (const field of STANDARD_FIELDS) {
    const value = data.fields[field.key] ?? ''
    editableValues[field.key] = value
    originalValues[field.key] = value
  }
}

async function load(): Promise<void> {
  status.value = 'loading'
  error.value = null
  saveSuccess.value = false
  try {
    const data = domainUpper.value === 'VIDEO'
      ? await videoMetadata(itemId.value)
      : await imageMetadata(itemId.value)
    applySnapshot(data)
    status.value = 'ready'
  } catch (err) {
    error.value = err
    status.value = 'error'
  }
}

function isLocked(key: string): boolean {
  return snapshot.value?.lockedFields.includes(key) ?? false
}

const hasChanges = computed(() => STANDARD_FIELDS.some((f) => editableValues[f.key] !== originalValues[f.key]))

/**
 * 只提交真正被编辑过的字段——后端 applyUserEdit 会把提交的每一个键都锁定
 * （spec §7.2 规则 2），如果把全部 5 个标准字段无差别地提交上去，会把用户根本
 * 没碰过的字段也意外锁死。这里刻意不做"是否锁定"复选框：锁定是保存的自然结果，
 * 不是另一份需要用户手动维护、可能与后端行为对不上的状态（preflight brief 原文
 * 与 spec §7.2 规则 2 的要求）。
 */
async function save(): Promise<void> {
  if (saving.value || !snapshot.value || !hasChanges.value) return

  const changed: Record<string, string> = {}
  for (const field of STANDARD_FIELDS) {
    if (editableValues[field.key] !== originalValues[field.key]) {
      changed[field.key] = editableValues[field.key]
    }
  }

  saving.value = true
  saveError.value = null
  saveSuccess.value = false
  try {
    const updated = domainUpper.value === 'VIDEO'
      ? await editVideoMetadata(itemId.value, changed)
      : await editImageMetadata(itemId.value, changed)
    applySnapshot(updated)
    saveSuccess.value = true
  } catch (err) {
    saveError.value = err instanceof ApiError ? err.message : '保存失败，请重试。'
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="admin-view">
    <h1 class="page-title">编辑元数据</h1>

    <div v-if="status === 'loading'" class="skeleton-rows">
      <div v-for="n in 5" :key="n" class="skeleton-row" />
    </div>

    <ErrorState v-else-if="status === 'error'" :error="error" :onRetry="load" />

    <template v-else-if="snapshot">
      <p class="status-summary">
        刮削状态 <span class="mono">{{ snapshot.scrapeStatus }}</span>
        <template v-if="snapshot.scrapeSource">
          · 来源 {{ sourceLabelOf(snapshot.scrapeSource) }}
          <span v-if="snapshot.scrapeSourceId" class="mono">#{{ snapshot.scrapeSourceId }}</span>
        </template>
      </p>

      <form class="edit-form" @submit.prevent="save">
        <div v-for="field in STANDARD_FIELDS" :key="field.key" class="field-row">
          <div class="field-header">
            <span class="field-label">{{ field.label }}</span>
            <span class="source-badge">{{ sourceLabelOf(snapshot.fieldSources[field.key]) }}</span>
            <span v-if="isLocked(field.key)" class="lock-badge" title="已锁定：刮削不会覆盖这个字段。">
              <svg class="lock-icon" viewBox="0 0 20 20" width="14" height="14" aria-hidden="true">
                <path
                  fill="currentColor"
                  d="M10 2a4 4 0 0 0-4 4v2H5a1 1 0 0 0-1 1v8a1 1 0 0 0 1 1h10a1 1 0 0 0 1-1v-8a1 1 0 0 0-1-1h-1V6a4 4 0 0 0-4-4Zm-2 6V6a2 2 0 1 1 4 0v2H8Z"
                />
              </svg>
              已锁定：刮削不会覆盖这个字段
            </span>
          </div>
          <textarea
            v-if="field.multiline"
            v-model="editableValues[field.key]"
            class="field-input"
            rows="4"
          />
          <input v-else type="text" v-model="editableValues[field.key]" class="field-input" />
        </div>

        <p v-if="saveError" class="error-text">{{ saveError }}</p>
        <p v-if="saveSuccess" class="success-text">保存成功。</p>

        <button type="submit" class="submit-btn" :disabled="saving || !hasChanges">
          {{ saving ? '保存中…' : '保存' }}
        </button>
      </form>
    </template>
  </div>
</template>

<style scoped>
.admin-view {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
  max-width: 640px;
}

.page-title {
  font-family: var(--display);
  font-weight: 600;
  font-size: var(--step-2);
  color: var(--text);
}

.status-summary {
  color: var(--dim);
  font-size: var(--step-0);
}

.mono {
  font-family: var(--font-data);
  color: var(--text);
}

.edit-form {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

.field-row {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-4);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--raised);
}

.field-header {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.field-label {
  font-size: var(--step--1);
  font-weight: 600;
  color: var(--text);
}

.source-badge {
  padding: 1px var(--space-2);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  color: var(--dim);
  font-family: var(--font-data);
  font-size: var(--step--1);
}

.lock-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--accent);
  font-size: var(--step--1);
  font-weight: 600;
}

.lock-icon {
  flex-shrink: 0;
}

.field-input {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--ground);
  color: var(--text);
  font-family: var(--font-body);
  font-size: var(--step-0);
  resize: vertical;
}

.field-input:focus-visible {
  border-color: var(--accent);
}

.submit-btn {
  align-self: flex-start;
  padding: var(--space-2) var(--space-4);
  border: none;
  border-radius: var(--radius);
  background: var(--accent);
  color: var(--shell-ground);
  font-family: var(--font-body);
  font-size: var(--step-0);
  font-weight: 700;
  cursor: pointer;
}

.submit-btn:disabled {
  opacity: 0.6;
  cursor: default;
}

.error-text {
  color: #d98a78;
}

.success-text {
  color: var(--text);
  font-weight: 600;
}

.skeleton-rows {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.skeleton-row {
  height: 3.5em;
  border-radius: var(--radius);
  background: var(--ground);
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
