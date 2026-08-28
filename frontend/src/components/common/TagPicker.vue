<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { listTags, createTag, tagsOf, setTags, type TargetKind } from '@/api/tags'
import { ApiError } from '@/api/client'
import type { TagSummary } from '@/api/types'

const props = defineProps<{
  domain: 'VIDEO' | 'IMAGE'
  targetId: number
  kind: TargetKind
}>()

const status = ref<'loading' | 'ready' | 'error'>('loading')
const allTags = ref<TagSummary[]>([])
const selected = ref<TagSummary[]>([])
const query = ref('')
const showSuggestions = ref(false)
const createErrorText = ref<string | null>(null)
const busy = ref(false)

async function load(): Promise<void> {
  status.value = 'loading'
  try {
    // 标签按域隔离（spec §6.2）：只取当前 domain 的词表，视频与图片标签互不混用。
    const [tags, current] = await Promise.all([listTags(props.domain), tagsOf(props.kind, props.targetId)])
    allTags.value = tags
    selected.value = current
    status.value = 'ready'
  } catch {
    status.value = 'error'
  }
}
onMounted(load)

const suggestions = computed<TagSummary[]>(() => {
  const q = query.value.trim().toLowerCase()
  const selectedIds = new Set(selected.value.map((tag) => tag.id))
  return allTags.value
    .filter((tag) => !selectedIds.has(tag.id))
    .filter((tag) => q === '' || tag.name.toLowerCase().includes(q))
    .slice(0, 8)
})

async function persist(tagIds: number[]): Promise<void> {
  busy.value = true
  try {
    selected.value = await setTags(props.kind, props.targetId, tagIds)
  } finally {
    busy.value = false
  }
}

async function addTag(tag: TagSummary): Promise<void> {
  if (selected.value.some((existing) => existing.id === tag.id)) return
  await persist([...selected.value.map((existing) => existing.id), tag.id])
  query.value = ''
  showSuggestions.value = false
}

async function removeTag(tag: TagSummary): Promise<void> {
  await persist(selected.value.filter((existing) => existing.id !== tag.id).map((existing) => existing.id))
}

async function handleEnter(): Promise<void> {
  const name = query.value.trim()
  if (!name) return
  createErrorText.value = null

  const exact = allTags.value.find((tag) => tag.name.toLowerCase() === name.toLowerCase())
  if (exact) {
    await addTag(exact)
    return
  }

  try {
    const created = await createTag(props.domain, name)
    allTags.value = [...allTags.value, created]
    await addTag(created)
  } catch (err) {
    // 建标签限 ADMIN（后端 TagController#create 上的 @PreAuthorize）——普通用户在这里
    // 会收到 403，得在输入框边上讲清楚，不能让它只安静地失败在控制台里。
    createErrorText.value = err instanceof ApiError && err.status === 403
      ? '没有权限创建新标签，请从已有标签中选择'
      : '创建标签失败，请重试'
  }
}
</script>

<template>
  <div class="tag-picker">
    <div class="chips">
      <span v-for="tag in selected" :key="tag.id" class="chip">
        <!-- 名字与移除按钮是兄弟节点，不是谁包着谁——RouterLink 渲染成 <a>，
             HTML5 不允许交互内容互相嵌套，之前正是在这类地方栽过一次
             （D 段 Task 12 review 抓到的问题），这次从一开始就按兄弟节点写。
             这是目前前端唯一显示标签本体、能点进 /tags/:id 的地方。 -->
        <RouterLink :to="{ name: 'tag', params: { id: tag.id } }" class="chip-name">
          {{ tag.name }}
        </RouterLink>
        <button type="button" class="remove" :disabled="busy" aria-label="移除标签" @click="removeTag(tag)">
          ×
        </button>
      </span>

      <div class="input-wrap">
        <input
          v-model="query"
          type="text"
          class="input"
          placeholder="添加标签…"
          :disabled="status !== 'ready' || busy"
          @focus="showSuggestions = true"
          @blur="showSuggestions = false"
          @keydown.enter.prevent="handleEnter"
        />
        <ul v-if="showSuggestions && suggestions.length > 0" class="suggestions">
          <li v-for="tag in suggestions" :key="tag.id">
            <button type="button" class="suggestion" @mousedown.prevent="addTag(tag)">
              {{ tag.name }}
            </button>
          </li>
        </ul>
      </div>
    </div>

    <p v-if="status === 'error'" class="hint error">标签加载失败。</p>
    <p v-if="createErrorText" class="hint error">{{ createErrorText }}</p>
  </div>
</template>

<style scoped>
.tag-picker {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.chips {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: 2px var(--space-1) 2px var(--space-3);
  border-radius: 999px;
  background: var(--accent-dim);
  color: var(--accent);
  font-size: var(--step--1);
}

.chip-name {
  color: inherit;
  text-decoration: none;
}

.chip-name:hover,
.chip-name:focus-visible {
  text-decoration: underline;
}

.remove {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.4em;
  height: 1.4em;
  border: none;
  border-radius: 50%;
  background: none;
  color: inherit;
  font-size: var(--step--1);
  line-height: 1;
  cursor: pointer;
}

.remove:hover {
  background: rgb(255 255 255 / 0.12);
}

.input-wrap {
  position: relative;
}

.input {
  min-width: 10em;
  padding: var(--space-1) var(--space-2);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--raised);
  color: var(--text);
  font-family: var(--font-body);
  font-size: var(--step--1);
}

.input:focus {
  outline: none;
  border-color: var(--accent);
}

.suggestions {
  position: absolute;
  top: calc(100% + var(--space-1));
  left: 0;
  z-index: 5;
  min-width: 12em;
  max-height: 14em;
  overflow-y: auto;
  margin: 0;
  padding: var(--space-1);
  list-style: none;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--raised);
  box-shadow: var(--elevation);
}

.suggestion {
  display: block;
  width: 100%;
  padding: var(--space-1) var(--space-2);
  border: none;
  border-radius: var(--radius);
  background: none;
  color: var(--text);
  font-size: var(--step--1);
  text-align: left;
  cursor: pointer;
}

.suggestion:hover {
  background: var(--ground);
}

.hint {
  font-size: var(--step--1);
  color: var(--dim);
}

.hint.error {
  color: var(--accent);
}
</style>
