import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 多环境 API 前缀：dev 走 proxy，不把 baseURL 写死在前端代码
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
