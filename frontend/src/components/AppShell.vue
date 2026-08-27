<script setup lang="ts">
import { watchEffect } from 'vue'
import { useRoute } from 'vue-router'
import DomainNav from './DomainNav.vue'
import SearchBox from './SearchBox.vue'

const route = useRoute()

watchEffect(() => {
  const domain = route.meta.domain as string | undefined
  if (domain) {
    document.body.dataset.domain = domain
  } else {
    delete document.body.dataset.domain
  }
})
</script>

<template>
  <div class="shell">
    <header v-if="!route.meta.bare" class="bar">
      <RouterLink :to="{ name: 'video-home' }" class="brand">MyMedia</RouterLink>
      <DomainNav />
      <SearchBox />
    </header>
    <main :class="{ bare: route.meta.bare }">
      <slot />
    </main>
  </div>
</template>

<style scoped>
.bar {
  display: flex;
  align-items: center;
  gap: var(--space-5);
  height: 56px;
  padding: 0 var(--space-5);
  border-bottom: 1px solid var(--line);
  background: var(--ground);
  position: sticky;
  top: 0;
  z-index: 10;
}

.brand {
  font-family: var(--display);
  font-weight: 800;
  font-size: var(--step-1);
  letter-spacing: -0.02em;
  color: var(--text);
  text-decoration: none;
}

main {
  padding: var(--space-6) var(--space-5);
  max-width: 1600px;
  margin: 0 auto;
}

main.bare {
  padding: 0;
  max-width: none;
}
</style>
