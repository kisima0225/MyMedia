import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  build: {
    outDir: 'dist',
    // 简历项目要能演示、也要能被读懂。sourcemap 让浏览器里点开的堆栈
    // 指回 .vue 与 .ts 源文件，代价只是几个 .map 文件。
    sourcemap: true,
  },
  server: {
    // 开发时 vite 跑 5173、后端跑 8080，所有 /api 请求转发过去。
    // 生产下前后端同源，这段配置根本不参与。
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: false },
    },
  },
})
