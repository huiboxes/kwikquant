import { test, expect, type Page } from '@playwright/test'

/**
 * 移动端横向溢出防回归 E2E(@local,需 dev server:后端 8080 + 前端 5173)。
 *
 * 背景:用户报告"很多页面初次访问看似适配,左右滑动后右侧空白/畸形"。根因是
 * 固定宽度组件/长文本/grid auto 轨道撑破视口,产生 <main> 或文档级横向滚动。
 * 本 spec 以 360x800(常见 Android 窄档,TopBar 挤压最敏感)与 390x844(iPhone 级)
 * 双视口遍历全部路由,断言:
 *   1. document 无横向滚动(scrollWidth <= clientWidth)
 *   2. <main>(内容滚动容器)无横向滚动——表格/标签条的容器级横滚是设计内
 *      (DESIGN.md 数字表 <760px 横向滚动 + 首列 sticky),只在各自 overflow 容器内滚
 *
 * CI 无后端时用 `--grep-invert @local` 排除;本地 `--grep @local` 跑。
 */

const MOBILE_VIEWPORTS = [
  { width: 360, height: 800 },
  { width: 390, height: 844 },
]

const APP_ROUTES = ['/', '/strategy', '/backtest', '/trade', '/portfolio', '/market', '/risk', '/history', '/settings']

/** 登录(用户不存在则先注册,invite 码由 V20 迁移播种) */
async function ensureLogin(page: Page) {
  await page.goto('/login')
  await page.fill('#username', 'mobile_e2e')
  await page.fill('#password', 'mobpass123')
  await page.click('button[type=submit]')
  // 登录成功跳 /;凭证错误停留 /login(出现 role=alert 提示)
  const logged = await page
    .waitForURL('**/', { timeout: 5000 })
    .then(() => true)
    .catch(() => false)
  if (!logged) {
    await page.goto('/register')
    await page.fill('#reg-username', 'mobile_e2e')
    await page.fill('#reg-email', 'mobile_e2e@example.com')
    await page.fill('#reg-password', 'mobpass123')
    await page.fill('#reg-confirm', 'mobpass123')
    await page.fill('#reg-invite', 'KWIK-DEV-001')
    await page.click('button[type=submit]')
    await page.waitForURL('**/', { timeout: 10000 })
  }
  // 钉死已认证态:AppLayout 渲染独有 <main>(waitForURL('**/') 只保证 URL 尾斜杠)
  await expect(page.locator('main')).toBeVisible()
}

async function assertNoHorizontalOverflow(page: Page, route: string) {
  await page.goto(route, { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(2500)
  const m = await page.evaluate(() => {
    const doc = document.documentElement
    const main = document.querySelector('main')
    return {
      docScroll: doc.scrollWidth,
      docClient: doc.clientWidth,
      mainScroll: main ? main.scrollWidth : 0,
      mainClient: main ? main.clientWidth : 0,
    }
  })
  expect(m.docScroll, `${route} 文档级横向溢出`).toBeLessThanOrEqual(m.docClient + 1)
  expect(m.mainScroll, `${route} main 容器横向溢出`).toBeLessThanOrEqual(m.mainClient + 1)
}

test.describe('移动端横向溢出防回归 @local', () => {
  test.use({ viewport: MOBILE_VIEWPORTS[0], isMobile: true, hasTouch: true })
  // 双视口 × 全路由逐页导航+等渲染,默认 30s 不够
  test.describe.configure({ timeout: 150_000 })

  test('公开页(login/register/landing)无横向溢出', async ({ page }) => {
    for (const vp of MOBILE_VIEWPORTS) {
      await page.setViewportSize(vp)
      for (const r of ['/login', '/register', '/']) {
        await assertNoHorizontalOverflow(page, r)
      }
    }
  })

  test('登录后全部 app 路由无横向溢出', async ({ page }) => {
    await ensureLogin(page)
    for (const vp of MOBILE_VIEWPORTS) {
      await page.setViewportSize(vp)
      for (const r of APP_ROUTES) {
        await assertNoHorizontalOverflow(page, r)
      }
    }
  })
})
