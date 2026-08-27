<script setup lang="ts">
import { computed, reactive } from 'vue'
import { formatDuration } from '@/lib/duration'
import EmptyState from '@/components/common/EmptyState.vue'
import type { VideoFileSummary, VideoGroupSummary, ContinueWatchingEntry } from '@/api/types'

const props = defineProps<{
  files: VideoFileSummary[]
  groups: VideoGroupSummary[]
  itemId: number
  structure: 'FLAT' | 'GROUPED'
  /** 续播进度——从 continueWatching() 一次性取来，按 fileId 匹配，不为每个文件单独请求。 */
  progress: ContinueWatchingEntry[]
}>()

const ROLE_LABEL: Record<VideoFileSummary['role'], string> = {
  PRIMARY: '正片',
  VERSION: '版本',
  EXTRA: '花絮',
  SUBTITLE: '字幕',
  TRAILER: '预告',
}

interface Section {
  key: string
  /** null：FLAT 结构的单一列表，不渲染节标题、也不可折叠。 */
  title: string | null
  files: VideoFileSummary[]
}

const byEpisodeIndex = (a: VideoFileSummary, b: VideoFileSummary): number =>
  (a.episodeIndex ?? Infinity) - (b.episodeIndex ?? Infinity)

const sections = computed<Section[]>(() => {
  if (props.structure !== 'GROUPED') {
    return [{ key: 'flat', title: null, files: [...props.files].sort(byEpisodeIndex) }]
  }

  const filesByGroup = new Map<number, VideoFileSummary[]>()
  const ungrouped: VideoFileSummary[] = []
  for (const file of props.files) {
    if (file.groupId == null) {
      ungrouped.push(file)
      continue
    }
    const bucket = filesByGroup.get(file.groupId)
    if (bucket) bucket.push(file)
    else filesByGroup.set(file.groupId, [file])
  }

  const groupSections = [...props.groups]
    .sort((a, b) => a.groupIndex - b.groupIndex)
    .map((group) => ({
      key: `group-${group.id}`,
      title: group.name,
      files: (filesByGroup.get(group.id) ?? []).sort(byEpisodeIndex),
    }))

  // 未归组的文件（分组信息缺失，或角色本就不挂在任何季下）不该被静默丢弃，
  // 单独放一节兜底，而不是伪装成某一季的内容。
  if (ungrouped.length > 0) {
    groupSections.push({ key: 'ungrouped', title: '其他文件', files: ungrouped.sort(byEpisodeIndex) })
  }
  return groupSections
})

// 折叠状态按 section.key 记，默认全部展开。
const collapsed = reactive<Record<string, boolean>>({})
function toggle(key: string): void {
  collapsed[key] = !collapsed[key]
}

const progressByFile = computed(() => {
  const map = new Map<number, ContinueWatchingEntry>()
  for (const entry of props.progress) map.set(entry.fileId, entry)
  return map
})

function leadingLabel(file: VideoFileSummary): string {
  return file.episodeIndex != null
    ? `E${String(file.episodeIndex).padStart(2, '0')}`
    : ROLE_LABEL[file.role]
}

function durationText(file: VideoFileSummary): string {
  return file.durationSeconds != null ? formatDuration(file.durationSeconds) : '--:--'
}

function resolutionText(file: VideoFileSummary): string | null {
  return file.width != null && file.height != null ? `${file.width}×${file.height}` : null
}

function isWatched(file: VideoFileSummary): boolean {
  return progressByFile.value.get(file.id)?.completed === true
}

function progressText(file: VideoFileSummary): string | null {
  const entry = progressByFile.value.get(file.id)
  if (!entry || entry.completed) return null
  const total = entry.durationSeconds != null ? formatDuration(entry.durationSeconds) : '--:--'
  return `${formatDuration(entry.positionSeconds)} / ${total}`
}

function playRoute(file: VideoFileSummary) {
  const entry = progressByFile.value.get(file.id)
  const resume = entry && !entry.completed ? entry.positionSeconds : undefined
  return {
    name: 'video-play',
    params: { fileId: file.id },
    query: resume != null ? { position: resume } : undefined,
  }
}
</script>

<template>
  <div class="episode-list">
    <template v-for="section in sections" :key="section.key">
      <button
        v-if="section.title"
        type="button"
        class="section-header"
        :aria-expanded="!collapsed[section.key]"
        @click="toggle(section.key)"
      >
        <span class="chevron" :class="{ open: !collapsed[section.key] }" aria-hidden="true" />
        <span class="title">{{ section.title }}</span>
        <span class="count">{{ section.files.length }} 集</span>
      </button>

      <div v-show="!section.title || !collapsed[section.key]" class="rows">
        <RouterLink
          v-for="file in section.files"
          :key="file.id"
          :to="playRoute(file)"
          class="row"
          :class="{ watched: isWatched(file) }"
        >
          <span v-if="file.episodeIndex != null" class="code">{{ leadingLabel(file) }}</span>
          <span v-else class="role-badge">{{ leadingLabel(file) }}</span>
          <span class="duration">{{ durationText(file) }}</span>
          <span v-if="resolutionText(file)" class="resolution">{{ resolutionText(file) }}</span>
          <span v-if="progressText(file)" class="progress-text">{{ progressText(file) }}</span>
          <span class="spacer" />
          <span class="play" aria-hidden="true">▶</span>
        </RouterLink>
      </div>
    </template>

    <EmptyState v-if="props.files.length === 0" title="这个条目还没有可播放的文件" />
  </div>
</template>

<style scoped>
.episode-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.section-header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  width: 100%;
  padding: var(--space-3) var(--space-2);
  margin-top: var(--space-4);
  border: none;
  border-bottom: 1px solid var(--line);
  background: none;
  color: var(--text);
  font-family: var(--display);
  font-weight: 600;
  font-size: var(--step-0);
  text-align: left;
  cursor: pointer;
}

.section-header:first-child {
  margin-top: 0;
}

.chevron {
  width: 0;
  height: 0;
  border-left: 5px solid var(--dim);
  border-top: 4px solid transparent;
  border-bottom: 4px solid transparent;
  transition: transform var(--dur-fast) var(--ease);
}

.chevron.open {
  transform: rotate(90deg);
}

.count {
  margin-left: auto;
  font-family: var(--font-data);
  font-size: var(--step--1);
  color: var(--dim);
}

.rows {
  display: flex;
  flex-direction: column;
}

.row {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  padding: var(--space-3) var(--space-3);
  border-left: 2px solid transparent;
  color: var(--text);
  text-decoration: none;
  transition: background var(--dur-fast) var(--ease);
}

.row:hover,
.row:focus-visible {
  background: var(--raised);
}

.row.watched {
  border-left-color: var(--accent);
}

.code {
  min-width: 3.5em;
  font-family: var(--font-data);
  font-variant-numeric: tabular-nums;
  font-size: var(--step-0);
  color: var(--text);
}

.role-badge {
  padding: 2px var(--space-2);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  font-size: var(--step--1);
  color: var(--dim);
}

.duration,
.resolution,
.progress-text {
  font-family: var(--font-data);
  font-variant-numeric: tabular-nums;
  font-size: var(--step--1);
  color: var(--dim);
}

.spacer {
  flex: 1;
}

.play {
  color: var(--accent);
  font-size: var(--step--1);
}
</style>
