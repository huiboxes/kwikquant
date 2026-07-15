/// <reference types="vitest/config" />
import path from 'node:path'
import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  // Dev proxy WS 认证:从 .env.local 读取 VITE_DEV_WS_COOKIE,注入到 WS 握手请求的 Cookie header。
  // 解决 localhost → dev.kwikquant.com 跨域时浏览器不自动带 cookie 的问题。
  // 生产环境同源部署不需要此机制(浏览器自动带 cookie)。
  const devWsCookie = env.VITE_DEV_WS_COOKIE || ''

  return {
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
        '/ws-native': {
          target: 'wss://dev.kwikquant.com',
          changeOrigin: true,
          ws: true,
          secure: false,
          configure: (proxy) => {
            if (devWsCookie) {
              // eslint-disable-next-line @typescript-eslint/no-explicit-any
              ;(proxy as any).on('proxy-req', (proxyReq: any) => {
                proxyReq.setHeader('Cookie', devWsCookie)
              })
            }
          },
        },
        '/ws': {
          target: 'wss://dev.kwikquant.com',
          changeOrigin: true,
          ws: true,
          secure: false,
          configure: (proxy) => {
            if (devWsCookie) {
              // eslint-disable-next-line @typescript-eslint/no-explicit-any
              ;(proxy as any).on('proxy-req', (proxyReq: any) => {
                proxyReq.setHeader('Cookie', devWsCookie)
              })
            }
          },
        },
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
  }
})
