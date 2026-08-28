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
//
// 匿名路由（meta.anonymous：/login、/s/:token）例外：那里本来就没有登录状态，
// 「登出」无事可做，跳转只会把分享页的访客弹到一个与他无关的登录页上。判断放在
// 这个注入的回调里而不是 client.ts 里，正是因为只有这一层才该认识 router。
// 这是一层纵深防御——具体的 401 源头（比如分享页里播放器的进度上报）应该在
// 源头就不发出去，见 VideoPlayer 的 reportProgress。
const auth = useAuthStore()
setUnauthorizedHandler(() => {
  if (router.currentRoute.value.meta.anonymous) return
  auth.logout()
  router.push({ name: 'login' }).catch(() => {
    // 导航被中断（比如已经在跳转别的地方）是正常竞态，不该冒泡成一条
    // 未处理的 promise rejection。
  })
})

app.mount('#app')
