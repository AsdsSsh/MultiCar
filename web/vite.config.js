import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Vite 配置：开发端口 5173，启用局域网访问
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    host: true,
    open: true
  }
})
