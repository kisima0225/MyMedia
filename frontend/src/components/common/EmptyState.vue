<script setup lang="ts">
// 空状态是邀请，不是句号：title 说明"这里没有什么"，hint 给出下一步动作。
defineProps<{
  title: string
  hint?: string
}>()
</script>

<template>
  <div class="empty-state">
    <p class="title">{{ title }}</p>
    <p v-if="hint" class="hint">{{ hint }}</p>
  </div>
</template>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-7) var(--space-5);
  text-align: center;
  color: var(--dim);
}

.title {
  font-family: var(--display);
  font-size: var(--step-1);
  color: var(--text);
}

.hint {
  font-size: var(--step-0);
  max-width: 40ch;
}
</style>
