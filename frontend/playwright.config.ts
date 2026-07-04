import { defineConfig, devices } from '@playwright/test'

/**
 * Playwright E2E 真实后端联调：
 * - webServer 起 `pnpm dev`（5173），vite proxy /api、/ws → 后端 8080（须已就绪）。
 * - globalSetup 准备确定性种子（脚手架阶段留占位，业务阶段实现）。
 * - 后端未起 → 种子准备失败 → spec fail（不静默跳过、不退化 mock）。
 */
export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/global-setup.ts',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'on-first-retry',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'pnpm dev',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
})
