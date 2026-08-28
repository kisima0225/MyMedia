<script setup lang="ts">
import { useRoute } from 'vue-router'

// 顶栏永远只有这两个顶层入口（spec §5.4：登录后顶层即两个域），别往里塞第三个。
const route = useRoute()
</script>

<template>
  <nav class="domain-nav" aria-label="媒体域">
    <RouterLink
      :to="{ name: 'video-home' }"
      class="link"
      :class="{ active: route.meta.domain === 'video' }"
    >
      视频
    </RouterLink>
    <RouterLink
      :to="{ name: 'image-home' }"
      class="link"
      :class="{ active: route.meta.domain === 'image' }"
    >
      图片
    </RouterLink>
  </nav>
</template>

<style scoped>
.domain-nav {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  height: 100%;
}

.link {
  position: relative;
  display: inline-flex;
  align-items: center;
  height: 100%;
  color: var(--dim);
  font-size: var(--step-0);
  font-weight: 600;
  text-decoration: none;
  transition: color var(--dur-fast) var(--ease);
}

.link:hover {
  color: var(--text);
}

.link.active {
  color: var(--text);
}

.link.active::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 2px;
  background: var(--accent);
  border-radius: 1px;
}
</style>
