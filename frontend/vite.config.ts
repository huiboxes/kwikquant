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
  // 开发态把 /api、/ws 反代到后端 8080。JWT 走 Authorization header（非 cookie），
  // 代理不必 changeOrigin，保留 Host 便于后端日志溯源。
  server: {
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: false },
      '/ws': { target: 'http://localhost:8080', changeOrigin: false, ws: true },
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
