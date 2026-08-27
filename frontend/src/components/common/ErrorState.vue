<script setup lang="ts">
import { computed } from 'vue'

// 错误说明发生了什么、怎么办，不道歉、不含糊。
const props = defineProps<{
  error: unknown
  onRetry?: () => void
}>()

const message = computed(() => {
  const err = props.error
  if (err instanceof Error) return err.message
  if (typeof err === 'string') return err
  if (err && typeof err === 'object' && 'message' in err) {
    const m = (err as { message?: unknown }).message
    if (typeof m === 'string') return m
  }
  return '未知错误。'
})
</script>

<template>
  <div class="error-state" role="alert">
    <p class="title">加载失败：{{ message }}</p>
    <p class="hint">稍后重试，或检查服务端日志。</p>
    <button v-if="onRetry" type="button" class="retry" @click="onRetry">重试</button>
  </div>
</template>

<style scoped>
.error-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-7) var(--space-5);
  text-align: center;
}

.title {
  font-size: var(--step-1);
  color: var(--text);
}

.hint {
  font-size: var(--step-0);
  color: var(--dim);
  max-width: 40ch;
}

.retry {
  margin-top: var(--space-2);
  padding: var(--space-2) var(--space-4);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--raised);
  color: var(--text);
  font-family: var(--font-body);
  font-size: var(--step-0);
  font-weight: 600;
  cursor: pointer;
  transition: border-color var(--dur-fast) var(--ease);
}

.retry:hover {
  border-color: var(--accent);
}
</style>
