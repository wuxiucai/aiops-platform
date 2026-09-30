import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 多环境 API 前缀：dev 走 proxy，不把 baseURL 写死在前端代码
export default defineConfig({
  plugins: [vue()],
  server: {
    host: '0.0.0.0',   // 同时监听 IPv4/IPv6，避免只绑 [::1] 导致 127.0.0.1 访问不通
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
      }
    }
  }
})
