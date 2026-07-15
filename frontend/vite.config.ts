/// <reference types="vitest/config" />
import path from 'node:path'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  // 开发态把 /api、/ws 反代到服务器后端(走 Cloudflare:443 → openresty /api → 8080)。
  // secure:false 因 CF 边缘证书;changeOrigin:true 保留 CF Host。
  server: {
    proxy: {
      '/api': { target: 'https://dev.kwikquant.com', changeOrigin: true, secure: false },
      '/ws-native': { target: 'wss://dev.kwikquant.com', changeOrigin: true, ws: true, secure: false },
      '/ws': { target: 'wss://dev.kwikquant.com', changeOrigin: true, ws: true, secure: false },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    // e2e/ 是 Playwright 套件（test() 由 @playwright/test 提供），不归 vitest 跑。
    exclude: ['e2e/**', 'node_modules/**', 'dist/**'],
  },
})
