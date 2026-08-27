<script setup lang="ts">
import { formatDuration } from '@/lib/duration'
</script>

<template>
  <main>
    <h1>MyMedia</h1>
    <p>前端骨架已就位。示例时长：{{ formatDuration(4567) }}</p>
  </main>
</template>
