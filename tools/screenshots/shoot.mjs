// 对着一个跑起来的 MyMedia 实例截 README 用的八张图。
//
// 用法：
//   npm i && npx playwright install chromium
//   node shoot.mjs --base http://localhost:8080 --user admin --pass admin
//
// 选择器不是抄计划 07 的组件命名猜的——写这份脚本时逐一打开了
// frontend/src/components 与 frontend/src/views 下的真实源码核对过，
// 并用 curl 打过一遍演示库的真实数据（/api/video/items、/api/image/nodes、
// /api/image/browse 等）确认了每一跳导航落在哪个节点上。仍然：
// 环境、演示数据一旦变过，这些选择器和导航路径也可能跟着过期，
// 跑之前先看一眼当前页面的真实 DOM 再改，不要照抄了事。
import { chromium } from 'playwright'
import { mkdir } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const arg = (name, fallback) => {
  const i = process.argv.indexOf(`--${name}`)
  return i >= 0 ? process.argv[i + 1] : fallback
}

const BASE = arg('base', 'http://localhost:8080')
const USER = arg('user', 'admin')
const PASS = arg('pass', 'admin')
const OUT = resolve(dirname(fileURLToPath(import.meta.url)), '../../docs/images')

const VIEWPORT = { width: 1440, height: 900 }
const NAV_TIMEOUT = 15000

// 前端把凭证存在 sessionStorage 里，每次请求自己拼一个 Authorization: Basic
// 头（frontend/src/api/client.ts），不是靠浏览器原生的 HTTP Basic 弹窗；
// 所以走真实登录表单最稳。httpCredentials 只是一层保险——后端
// SecurityConfig 确实开了 httpBasic()，理论上某个请求在凭证还没写进
// sessionStorage 之前 401 时，浏览器有可能弹原生认证框把无头截图卡死，
// 这里让 Playwright 静默应答掉，不依赖它也不会造成任何副作用。
async function login(page) {
  await page.goto(`${BASE}/login`)
  await page.fill('input[name="username"]', USER)
  await page.fill('input[name="password"]', PASS)
  await page.click('button[type="submit"]')
  await page.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: NAV_TIMEOUT })
}

async function settle(page, ms = 1200) {
  // 给封面图、字体、进场动画一点时间收尾，避免截到"半加载"的中间状态。
  await page.waitForTimeout(ms)
}

// ── 八张图，每张都是一个独立的函数：都从已登录状态出发，自己重新导航到
// 需要的页面，不依赖上一张图留下的路由状态——这样任意一张单独重跑，也不会
// 因为"少了上一步的副作用"而截出一张不对的图。
//
// 这里刻意不给 waitForSelector 包 .catch(() => {})：选择器已经对着真实源码
// 核对过，一旦真的找不到，让脚本直接崩在那一步、报出是哪张图哪个选择器，
// 比"默默截一张空图，指望人肉眼看出来"更可靠。

async function shotVideoHome(page) {
  await page.goto(`${BASE}/video`)
  // VideoHomeView 按媒体库分段（.library-section），段内是 VideoCard 的
  // 网格；.card 是 VideoCard.vue 的根元素本身（RouterLink 渲染成 <a>），
  // 不是 .card 下面还有个 <a>。限定在 .library-section 里，避免"继续观看"
  // 那一行（ContinueRow.vue 用的也是 .card）一旦有数据时抢先被选中——
  // 演示库刚建好、没人看过东西，那一行现在不会出现，但选择器仍然写得精确。
  await page.waitForSelector('.library-section .card', { timeout: NAV_TIMEOUT })
  await settle(page)
}

async function shotItemDetail(page) {
  await page.goto(`${BASE}/video`)
  await page.waitForSelector('.library-section .card', { timeout: NAV_TIMEOUT })
  await page.click('.library-section .card')
  // ItemDetailView 加载完成前是 .skeleton-banner；等主操作按钮出现
  // 才说明真实数据（标题、播放按钮）已经渲染出来，不是骨架屏。
  await page.waitForSelector('.item-detail .action.primary', { timeout: NAV_TIMEOUT })
  await settle(page)
}

async function shotPlayer(page) {
  await page.goto(`${BASE}/video`)
  await page.waitForSelector('.library-section .card', { timeout: NAV_TIMEOUT })
  await page.click('.library-section .card')
  await page.waitForSelector('.item-detail .action.primary', { timeout: NAV_TIMEOUT })
  // 条目详情页的"播放"是个 RouterLink（primaryPlayTarget 存在时渲染成
  // <a class="action primary">，否则是禁用的 <button>，两者共享同一个
  // class，用 a.action.primary 精确只选可点的那种）。
  await page.click('.item-detail a.action.primary')
  // ScrubBar.vue 的根类是 .scrub，不是 .scrub-bar。
  await page.waitForSelector('.scrub', { timeout: NAV_TIMEOUT })
  // 播放器页自己的播放/暂停切换按钮初始文案也是"播放"（VideoPlayer.vue），
  // 限定在 .player .row 里，不会跟前一步已经离开的条目详情页选择器混淆
  // （那时已经完成导航，旧元素也不在 DOM 里了，这里只是让语义更明确）。
  await page.click('.player .row button:has-text("播放")')
  // 只等一小段，别等满 2.5 秒——VideoPlayer.vue 的控制条在播放中静止 2.5 秒
  // 后会自动淡出（armIdleTimer），扣满这个窗口再去悬停，鼠标移动虽然会
  // 重新唤出控制条，但那一下 pointermove 命中的是刚淡出、pointer-events:
  // none 的旧状态，ScrubBar.vue 自己的 @pointermove 处理器根本收不到事件，
  // 悬停预览帧永远不会出现（吃过这个亏：截图能出、暂停/播放按钮都在，
  // 唯独预览帧空着）。留在窗口以内，悬停才是有效的。
  await page.waitForTimeout(900) // 给视频真正起播、进度往前挪一点
  const bar = await page.$('.scrub')
  if (bar) {
    const box = await bar.boundingBox()
    if (box) {
      // 分两步移动而不是一次瞬移到目标点：先落到轨道上，再挪到 40% 处，
      // 让浏览器把它当成一次真实的"移入 + 移动"，onHover 才会算出悬停状态。
      await page.mouse.move(box.x + box.width * 0.1, box.y + box.height / 2)
      await page.mouse.move(box.x + box.width * 0.4, box.y + box.height / 2, { steps: 8 })
    }
  }
  await page.waitForTimeout(400) // 悬停预览帧（从雪碧图换算）渲染出来，仍在 2.5 秒的隐藏窗口以内
}

async function shotImageHome(page) {
  await page.goto(`${BASE}/image`)
  // BookCard.vue 的根是 <div class="book">，不是 .book-card（那个类名在
  // 整个前端里不存在）。.cover-link 是封面那个 RouterLink。
  await page.waitForSelector('.book .cover-link', { timeout: NAV_TIMEOUT })
  await settle(page)
}

async function shotReader(page) {
  await page.goto(`${BASE}/image`)
  // 首页最先出现的是"继续阅读"横条——演示库预置了两条阅读进度
  // （curl /api/image/continue-reading 核对过），它的封面链接直接指向
  // 阅读器，会绕过下面这套"根节点 -> 子节点"的两跳导航；.library-section
  // 限定只选真正的根节点网格（NodeGrid 在 ImageHomeView 里包在
  // .library-section 下，继续阅读横条包在 .continue-row 下，两者都渲染
  // .book，不加限定选择器会先踩进继续阅读那一行）。
  await page.waitForSelector('.library-section .book .cover-link', { timeout: NAV_TIMEOUT })
  await page.click('.library-section .book .cover-link')
  // .node-browse 是 NodeBrowseView 的根类，.reader 是 ReaderView 的根类，
  // 两者互斥且分别只在各自组件里出现——用它们确认真的落到了哪一页，不能
  // 复用 .book .cover-link 这种在浏览页判定前一步也可能命中的选择器：
  // 路由懒加载是异步组件，点击后 URL 和 DOM 不是同一时刻更新，旧页面的
  // 元素会在新组件真正挂载前继续留在 DOM 里，用同一个选择器连续判断两次
  // 很容易在旧页面上误触第二下（实测踩过这个坑：click 后紧接着用同一个
  // .book .cover-link 去 waitForSelector，会立刻在还没换页的旧 DOM 上
  // "成功"，第二次 click 因此点到旧元素，元素半路被卸载，最终 30s 超时）。
  await page.waitForSelector('.node-browse, .reader', { timeout: NAV_TIMEOUT })
  if (await page.$('.node-browse')) {
    // 演示库的根节点（"图集"/"漫画"）都是 browsable && !readable，点封面
    // 落在浏览页；浏览页下一层的子节点都是 readable && !browsable 的实际
    // 本子（两者都用 curl 核对过 /api/image/nodes、/api/image/browse 的
    // 真实返回），浏览页只有一个 NodeGrid，不需要再额外限定容器。
    await page.waitForSelector('.book .cover-link', { timeout: NAV_TIMEOUT })
    await page.click('.book .cover-link')
    await page.waitForSelector('.reader', { timeout: NAV_TIMEOUT })
  }
  // PageView.vue：图片 onload 之前有 .placeholder 顶着、<img> 带 .hidden；
  // 等 .page-img 上的 .hidden 摘掉，才说明真的解码渲染出来了，不是占位块。
  await page.waitForSelector('.page-img:not(.hidden)', { timeout: NAV_TIMEOUT })
  await settle(page, 800)
}

async function shotSearch(page) {
  await page.goto(`${BASE}/search?q=e`)
  // SearchView 按域分段，每段套一层 [data-domain="..."]；用 curl 核对过
  // /api/search?q=e 在演示库里视频域有命中（Blender 演示剧集、Sintel），
  // 图片域没有——所以只等视频段的卡片，不等图片段。
  await page.waitForSelector('[data-domain="video"] .card', { timeout: NAV_TIMEOUT })
  await settle(page)
}

async function shotAdminLibraries(page) {
  await page.goto(`${BASE}/admin/libraries`)
  // LibraryAdminView 的表格类是 .lib-table，不是 .library-table。
  await page.waitForSelector('table.lib-table', { timeout: NAV_TIMEOUT })
  await settle(page)
}

async function shotAdminUpload(page) {
  await page.goto(`${BASE}/admin/upload`)
  // .upload-form 只在媒体库下拉加载完成（librariesStatus === 'ready'）后
  // 才渲染，等它出现就跳过了骨架屏状态。
  await page.waitForSelector('.upload-form', { timeout: NAV_TIMEOUT })
  await settle(page)
}

// 顺序即 README 里的出现顺序。
const SHOTS = [
  { file: '01-视频首页.png', run: shotVideoHome },
  { file: '02-条目详情.png', run: shotItemDetail },
  { file: '03-播放器.png', run: shotPlayer },
  { file: '04-图片首页.png', run: shotImageHome },
  { file: '05-阅读器.png', run: shotReader },
  { file: '06-全局搜索.png', run: shotSearch },
  { file: '07-媒体库管理.png', run: shotAdminLibraries },
  { file: '08-分片上传.png', run: shotAdminUpload },
]

const run = async () => {
  await mkdir(OUT, { recursive: true })
  const browser = await chromium.launch()
  const context = await browser.newContext({
    viewport: VIEWPORT,
    deviceScaleFactor: 2,
    httpCredentials: { username: USER, password: PASS },
  })
  const page = await context.newPage()

  await login(page)

  for (const shot of SHOTS) {
    try {
      await shot.run(page)
    } catch (err) {
      console.error(`截图失败：${shot.file}`)
      throw err
    }
    await page.screenshot({ path: resolve(OUT, shot.file), fullPage: false })
    console.log(`已截图：${shot.file}`)
  }

  await browser.close()
}

run().catch((err) => {
  console.error(err)
  process.exit(1)
})
