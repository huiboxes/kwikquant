import { test, expect } from '@playwright/test'

/**
 * 策略工作台 E2E(@local tag,需 dev server:后端 8080 + 前端 5173)。
 *
 * CI 无后端时用 `--grep-invert @local` 排除;本地 `--grep @local` 跑。
 * 前置:后端跑起来 + 测试账号(devuser/devpass)+ 已建一个策略。
 *
 * DOM 断言,不靠截图(约束:不用 weavefox-browser 截图测试)。
 */
test('策略工作台多 tab + 跑回测 @local', async ({ page }) => {
  // 登录
  await page.goto('http://localhost:5173/login')
  await page.fill('[name=username]', 'devuser')
  await page.fill('[name=password]', 'devpass')
  await page.click('button[type=submit]')
  await page.waitForURL('/')

  // 点首个策略"编辑代码" → 跳工作台(多 tab URL)
  await page.click('[data-testid=edit-strategy]:first-of-type')
  await page.waitForURL(/\/workbench/)

  // 验 TopBar + TabBar + 编辑器 + 右栏
  await expect(page.locator('text=kwikquant.io')).toBeVisible()
  await expect(page.locator('text=workbench')).toBeVisible()
  await expect(page.locator('.monaco-editor')).toBeVisible()
  await expect(
    page.locator('text=回测结果').or(page.locator('text=Run Live')),
  ).toBeVisible()

  // 点 Backtest → AlertDialog 确认
  await page.click('text=Backtest')
  await expect(page.locator('text=用所选参数跑回测')).toBeVisible()
  await page.click('text=确认')
  // 等右栏 Complete(轮询可能要时间,放宽 timeout)
  await expect(page.locator('text=Complete')).toBeVisible({ timeout: 30000 })
})
