import { chromium } from '@playwright/test'

const b = await chromium.launch({
  channel: 'chrome',
  headless: true,
  args: [
    '--no-proxy-server',
    '--no-sandbox',
    '--proxy-server=direct://',
    '--proxy-bypass-list=<-loopback>',
    '--host-resolver-rules=MAP localhost 127.0.0.1,DIRECT',
  ],
})
const p = await b.newPage({ viewport: { width: 1280, height: 800 } })
await p.goto('http://localhost:5173/login', { waitUntil: 'domcontentloaded', timeout: 60000 })
await p.waitForTimeout(2000)
await p.screenshot({ path: '/tmp/theme-dark.png' })

// 切亮主题:直接 DOM 操作移除 dark class(验证 CSS token 双主题切换可视)
// themeStore persist 切换由单测覆盖,此处只验证 :root vs .dark token 视觉差异
await p.evaluate(() => document.documentElement.classList.remove('dark'))
await p.waitForTimeout(500)
await p.screenshot({ path: '/tmp/theme-light.png' })

await b.close()
console.log('screenshots done: /tmp/theme-dark.png /tmp/theme-light.png')
