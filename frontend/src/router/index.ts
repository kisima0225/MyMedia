import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { hasCredential } from '@/api/client'
import { useAuthStore } from '@/stores/auth'

/**
 * 路由的 meta.domain 决定外壳给 <body> 设哪个 data-domain，
 * 也就决定了整套 CSS 令牌切到哪一域（tokens.css）。
 * 不写 domain 的路由用中性外壳色。
 */
const routes: RouteRecordRaw[] = [
  { path: '/', redirect: { name: 'video-home' } },
  { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'),
    meta: { anonymous: true } },

  { path: '/video', name: 'video-home', component: () => import('@/views/video/VideoHomeView.vue'),
    meta: { domain: 'video' } },
  { path: '/video/items/:id', name: 'video-item', props: true,
    component: () => import('@/views/video/ItemDetailView.vue'), meta: { domain: 'video' } },
  { path: '/video/play/:fileId', name: 'video-play', props: true,
    component: () => import('@/views/video/PlayerView.vue'), meta: { domain: 'video' } },
  { path: '/video/browse', name: 'video-browse',
    component: () => import('@/views/video/VideoBrowseView.vue'), meta: { domain: 'video' } },
  { path: '/video/search', name: 'video-search',
    component: () => import('@/views/video/VideoSearchView.vue'), meta: { domain: 'video' } },

  { path: '/image', name: 'image-home', component: () => import('@/views/image/ImageHomeView.vue'),
    meta: { domain: 'image' } },
  { path: '/image/nodes/:id', name: 'image-node', props: true,
    component: () => import('@/views/image/NodeBrowseView.vue'), meta: { domain: 'image' } },
  { path: '/image/nodes/:id/read', name: 'image-read', props: true,
    component: () => import('@/views/image/ReaderView.vue'),
    meta: { domain: 'image', bare: true } },
  { path: '/image/search', name: 'image-search',
    component: () => import('@/views/image/ImageSearchView.vue'), meta: { domain: 'image' } },

  { path: '/search', name: 'search', component: () => import('@/views/SearchView.vue') },
  { path: '/favorites', name: 'favorites', component: () => import('@/views/FavoritesView.vue') },
  { path: '/tags/:id', name: 'tag', props: true, component: () => import('@/views/TagView.vue') },

  { path: '/s/:token', name: 'share', props: true,
    component: () => import('@/views/ShareView.vue'), meta: { anonymous: true, bare: true } },

  { path: '/admin/libraries', name: 'admin-libraries',
    component: () => import('@/views/admin/LibraryAdminView.vue'), meta: { admin: true } },
  { path: '/admin/upload', name: 'admin-upload',
    component: () => import('@/views/admin/UploadView.vue'), meta: { admin: true } },
  { path: '/admin/scrape', name: 'admin-scrape',
    component: () => import('@/views/admin/ScrapeReviewView.vue'), meta: { admin: true } },
  { path: '/admin/metadata/:domain/:id', name: 'admin-metadata', props: true,
    component: () => import('@/views/admin/MetadataEditView.vue'), meta: { admin: true } },

  { path: '/:pathMatch(.*)*', redirect: { name: 'video-home' } },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: (_to, _from, saved) => saved ?? { top: 0 },
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!auth.user && hasCredential()) {
    await auth.restore()
  }
  if (to.meta.anonymous) {
    return true
  }
  if (!auth.user) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.admin && !auth.isAdmin) {
    // 不是 ADMIN 就当这个页面不存在——与后端「404 而非 403」同一条规矩
    return { name: 'video-home' }
  }
  return true
})
