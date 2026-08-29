# 截图工具

给根 `README.md` 的「界面预览」一节产八张真实截图的独立工具。不是前端工程的一部分，也不接入 Maven 构建——`docker compose up` 起一个真实实例、跑一遍 Playwright，才是它唯一的用途。

## 怎么跑

1. 先起一个跑起来的 MyMedia 实例（仓库根目录）：

   ```bash
   docker compose up -d
   ```

   确认健康：`curl -s http://localhost:8080/actuator/health` 应该返回 `{"status":"UP",...}`。演示数据（视频、图片条目）需要已经存在——没有数据的话有几张图（条目详情、播放器、阅读器）会因为找不到可点的卡片而直接报错退出，这是设计如此，不是 bug：与其静默截一张空白图，不如让脚本崩在具体哪一步。

2. 安装依赖、装浏览器：

   ```bash
   cd tools/screenshots
   npm install
   npx playwright install chromium
   ```

3. 跑：

   ```bash
   node shoot.mjs --base http://localhost:8080 --user admin --pass admin
   ```

   或用 `npm run shoot`（默认参数就是上面这三个）。

产物落在 `docs/images/`（相对仓库根），八个 PNG，覆盖视频首页、条目详情、播放器、图片首页、阅读器、全局搜索、媒体库管理、分片上传。**每次跑完建议肉眼过一遍**——`waitForSelector` 等到了元素不代表内容一定"好看"（比如封面图还没解码完），脚本本身不做这层判断。

## 为什么不放进 `frontend/`

`frontend/package.json` 现在只有 3 个运行时依赖和 6 个开发依赖，这个极简是刻意的（计划 07 的 Global Constraints 明写"不引组件库、不引 CSS 框架、不引 HTTP 客户端库"）。Playwright 会往 `npm ci` 里加一大块，而它跟前端本身一点关系都没有——它是**产文档资产的工具**，不参与任何运行时。放进 `tools/` 并且不接进 Maven 生命周期，主构建一秒都不会变慢，`frontend/` 的依赖列表也不会因为一个截图脚本多出一整条 Chromium。

## 选择器会过期

`shoot.mjs` 里每个选择器都是对着当时的真实组件源码 + 演示数据核对过的（不是抄计划文档里的猜测），但组件改版、演示数据换了之后，这些选择器和导航路径大概率会跟着过期。跑之前先打开页面看一眼真实 DOM，跑完对着截图核实一遍——不要假设脚本永远正确。
