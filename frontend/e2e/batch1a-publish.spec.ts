import { test, expect } from '@playwright/test'

/**
 * 批 1a E2E — publish 闭环(spec §5 step 17,本地真实后端跑,不进 CI)。
 *
 * 前置:
 *  - docker compose up -d postgres(5432)
 *  - mvnw spring-boot:run(8080,不起 Worker)
 *  - globalSetup 用 register 端点创建测试用户 alice/password123(若已存在则登录)
 *
 * 闭环:登录 → 新建策略 → 工作区 → publish → GET codes 显示 PUBLISHED。
 *
 * 注:CodeFuse 沙盒 socks 代理可能阻塞 Playwright Chrome 访问 localhost,
 *    此 spec 需在无代理环境(用户本地)跑。CI 不跑(playwright.config forbidOnly + 不进 CI)。
 */
test.describe('批 1a publish 闭环', () => {
  test('登录 → 新建策略 → publish → 已发布', async ({ page }) => {
    // 1. 登录
    await page.goto('/login')
    await page.fill('#username', 'alice')
    await page.fill('#password', 'password123')
    await page.click('button[type=submit]')
    await page.waitForURL('/', { timeout: 10_000 })

    // 2. 新建策略
    await page.click('a[href="/strategies/new"]')
    await page.waitForURL('/strategies/new')
    await page.fill('#name', 'E2E 发布测试')
    await page.fill('#symbol', 'BTC/USDT')
    await page.click('button[type=submit]')

    // 3. 跳工作区编码态
    await page.waitForURL(/\/strategies\/\d+/, { timeout: 10_000 })

    // 4. publish
    const publishBtn = page.locator('button', { hasText: '发布' })
    await expect(publishBtn).toBeVisible()
    await publishBtn.click()

    // 5. 状态变"已发布"
    await expect(page.locator('button', { hasText: '已发布' })).toBeVisible({
      timeout: 10_000,
    })
  })
})
