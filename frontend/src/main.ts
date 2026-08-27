import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { router } from './router'
import App from './App.vue'
import { setUnauthorizedHandler } from '@/api/client'
import { useAuthStore } from '@/stores/auth'

import './styles/fonts.css'
import './styles/tokens.css'
import './styles/base.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)

// 401 时清身份、跳登录页。放在 client.ts 之外是有意的：
// 数据层不该知道路由层的存在，见 api/client.ts 里 setUnauthorizedHandler 的注释。
const auth = useAuthStore()
setUnauthorizedHandler(() => {
  auth.logout()
  router.push({ name: 'login' }).catch(() => {
    // 导航被中断（比如已经在跳转别的地方）是正常竞态，不该冒泡成一条
    // 未处理的 promise rejection。
  })
})

app.mount('#app')
