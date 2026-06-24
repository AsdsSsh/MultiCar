import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'

// Vite 配置：开发端口 5173，构建输出到 web/dist/
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    host: true,
    open: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8887',
        changeOrigin: true
      },
      '/ws': {
        target: 'ws://localhost:8887',
        ws: true
      }
    }
  },
  build: {
    outDir: path.resolve(__dirname, 'dist'),
    emptyOutDir: true
  }
})
