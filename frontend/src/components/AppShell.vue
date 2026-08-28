<script setup lang="ts">
import { onMounted, onUnmounted, ref, watchEffect } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import DomainNav from './DomainNav.vue'
import SearchBox from './SearchBox.vue'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

watchEffect(() => {
  const domain = route.meta.domain as string | undefined
  if (domain) {
    document.body.dataset.domain = domain
  } else {
    delete document.body.dataset.domain
  }
})

// ── 顶栏用户菜单：当前用户名 + 下拉，ADMIN 才看得到三个管理入口
// （对应路由 admin-libraries/admin-upload/admin-scrape 都已在 meta.admin 上
// 做了守卫，这里只是不把入口露出来，不是唯一的权限防线）。
// 点击外部关闭沿用 SearchBox.vue 同一套 mousedown 惯例。
const menuOpen = ref(false)
const menuEl = ref<HTMLElement | null>(null)

function toggleMenu(): void {
  menuOpen.value = !menuOpen.value
}

function closeMenu(): void {
  menuOpen.value = false
}

function onDocumentMousedown(event: MouseEvent): void {
  if (menuEl.value?.contains(event.target as Node)) return
  menuOpen.value = false
}

async function logout(): Promise<void> {
  closeMenu()
  auth.logout()
  await router.push({ name: 'login' })
}

onMounted(() => document.addEventListener('mousedown', onDocumentMousedown))
onUnmounted(() => document.removeEventListener('mousedown', onDocumentMousedown))
</script>

<template>
  <div class="shell">
    <header v-if="!route.meta.bare" class="bar">
      <RouterLink :to="{ name: 'video-home' }" class="brand">MyMedia</RouterLink>
      <DomainNav />
      <SearchBox />

      <div v-if="auth.user" ref="menuEl" class="user-menu">
        <button type="button" class="user-btn" @click="toggleMenu">
          {{ auth.user.displayName }}
        </button>
        <div v-if="menuOpen" class="dropdown" role="menu">
          <template v-if="auth.isAdmin">
            <RouterLink :to="{ name: 'admin-libraries' }" class="item" role="menuitem" @click="closeMenu">
              媒体库管理
            </RouterLink>
            <RouterLink :to="{ name: 'admin-upload' }" class="item" role="menuitem" @click="closeMenu">
              上传
            </RouterLink>
            <RouterLink :to="{ name: 'admin-scrape' }" class="item" role="menuitem" @click="closeMenu">
              刮削确认
            </RouterLink>
            <div class="sep" role="separator" />
          </template>
          <button type="button" class="item logout" role="menuitem" @click="logout">退出登录</button>
        </div>
      </div>
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

.user-menu {
  position: relative;
  flex-shrink: 0;
}

.user-btn {
  padding: var(--space-1) var(--space-3);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--raised);
  color: var(--text);
  font-family: var(--font-body);
  font-size: var(--step--1);
  font-weight: 600;
  white-space: nowrap;
  cursor: pointer;
  transition: border-color var(--dur-fast) var(--ease);
}

.user-btn:hover {
  border-color: var(--accent);
}

.dropdown {
  position: absolute;
  top: calc(100% + var(--space-2));
  right: 0;
  z-index: 20;
  display: flex;
  flex-direction: column;
  min-width: 160px;
  padding: var(--space-2);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--raised);
  /* --elevation 只在两个域下定义（tokens.css）；顶栏在任何页面都要显示，
     这里给一个通用兜底阴影，中性壳与域内壳都能用。 */
  box-shadow: var(--elevation, 0 8px 24px -6px rgb(0 0 0 / 0.5));
}

.item {
  display: block;
  width: 100%;
  padding: var(--space-2) var(--space-3);
  border: none;
  border-radius: var(--radius);
  background: transparent;
  color: var(--text);
  font-family: var(--font-body);
  font-size: var(--step-0);
  text-align: left;
  text-decoration: none;
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease);
}

.item:hover,
.item:focus-visible {
  background: var(--ground);
}

.item.logout {
  color: var(--dim);
}

.sep {
  height: 1px;
  margin: var(--space-2) 0;
  background: var(--line);
}
</style>
