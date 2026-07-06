import { test, expect } from '@playwright/test'

/**
 * 批 1b E2E — 回测闭环(spec §5 step 25,本地真实后端 + Python Worker 跑,不进 CI)。
 *
 * 前置(plan §25 Host Python 方案):
 *  - docker compose up -d postgres(5432)
 *  - host Python:pip install -r requirements-worker.txt
 *  - kwikquant.worker.script=kwikquant_worker/worker_server.py(underscore,默认 hyphen 是 bug)
 *  - kwikquant.worker.python-command=python(或 python3)
 *  - mvnw spring-boot:run(8080,host 跑 Java,runner 直接 spawn host python)
 *  - globalSetup 用 register 端点创建测试用户 alice/password123(若已存在则登录)
 *
 * 闭环:登录 → 新建/打开策略 → 写代码 → publish → 提交回测 → 轮询 COMPLETED → 看 equity + 指标 + trades。
 *
 * 注:CodeFuse 沙盒 socks 代理可能阻塞 Playwright Chrome 访问 localhost,
 *    此 spec 需在无代理环境(用户本地)跑。CI 不跑(playwright.config forbidOnly + 不进 CI)。
 *    Worker subprocess 模型(PythonSubprocessBacktestRunner),不起长驻 worker 容器。
 */
test.describe('批 1b 回测闭环', () => {
  test('登录 → publish → 提交回测 → COMPLETED → 看 equity + 指标 + trades', async ({ page }) => {
    // 1. 登录
    await page.goto('/login')
    await page.fill('#username', 'alice')
    await page.fill('#password', 'password123')
    await page.click('button[type=submit]')
    await page.waitForURL('/', { timeout: 10_000 })

    // 2. 新建策略
    await page.click('a[href="/strategies/new"]')
    await page.waitForURL('/strategies/new')
    await page.fill('#name', 'E2E 回测测试')
    await page.fill('#symbol', 'BTC/USDT')
    await page.click('button[type=submit]')
    await page.waitForURL(/\/strategies\/\d+/, { timeout: 10_000 })

    // 3. publish(若未发布)
    const publishBtn = page.locator('button', { hasText: '发布' })
    if (await publishBtn.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await publishBtn.click()
      await expect(page.locator('button', { hasText: '已发布' })).toBeVisible({
        timeout: 10_000,
      })
    }

    // 4. 跳回测态(StageBreadcrumb 点"回测")
    await page.click('button[role="button"]:has-text("回测"), button:has-text("回测")')

    // 5. 填回测表单 + 提交
    await page.fill('#bt-symbol', 'BTC/USDT')
    await page.fill('#bt-capital', '10000')
    await page.fill('#bt-start', '2026-06-01T00:00')
    await page.fill('#bt-end', '2026-07-01T00:00')
    await page.click('button[type=submit]:has-text("提交回测")')

    // 6. 轮询到 COMPLETED(回测跑几分钟,超时 180s)
    await expect(page.locator('text=回测进行中')).toBeVisible({ timeout: 10_000 })
    await expect(page.locator('text=Report #')).toBeVisible({ timeout: 180_000 })

    // 7. 看 equity + 指标 + trades
    await expect(page.locator('text=核心指标')).toBeVisible()
    await expect(page.locator('text=交易明细')).toBeVisible()
    await expect(page.locator('text=总收益率')).toBeVisible()
    await expect(page.locator('text=最大回撤')).toBeVisible()
  })
})
