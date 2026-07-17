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
    // 开发态把 /api、/ws 反代到本地后端(localhost:8080)。
    // 切换远程服务器时改回 https://dev.kwikquant.com + secure:false。
    //
    // WS 鉴权:后端 WebSocketAuthInterceptor 走 refresh_token cookie。线上同源 nginx 自动透传;
    // dev 走 vite proxy,浏览器跨站/同源 WS 握手不一定带 Strict cookie,故 proxy-req 显式处理:
    //   1) 浏览器自带 Cookie(refresh_token)→ 透传,零维护;
    //   2) 没带 → 兜底注入 VITE_DEV_WS_COOKIE(.env.local,token 7d 过期需更新)。
    // 重连零成本:每次握手/重连 proxy 都自动带 cookie,无需像 Bearer 那样重连前刷新 token。
    server: {
      proxy: {
        '/api': { target: 'http://localhost:8080', changeOrigin: true },
        '/ws-native': {
          target: 'ws://localhost:8080',
          changeOrigin: true,
          ws: true,
          configure: (proxy) => {
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            ;(proxy as any).on('proxy-req', (proxyReq: any, req: any) => {
              const incoming = req?.headers?.cookie
              if (!incoming && devWsCookie) proxyReq.setHeader('Cookie', devWsCookie)
            })
          },
        },
        '/ws': {
          target: 'ws://localhost:8080',
          changeOrigin: true,
          ws: true,
          configure: (proxy) => {
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            ;(proxy as any).on('proxy-req', (proxyReq: any, req: any) => {
              const incoming = req?.headers?.cookie
              if (!incoming && devWsCookie) proxyReq.setHeader('Cookie', devWsCookie)
            })
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
