/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

// 冷白画布契约(对齐长桥式中性底):#EEF0F2 画布 + #FFF 白卡 + 冷蓝黑文字。
// 品牌橙只出现在主 CTA / focus / 内联强调,选中态与装饰场景走中性色。
// 这里锁死 index.css 值层,防止暖色回潮。
const css = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), '../index.css'), 'utf8')

/** 抽 :root 块(到第一个 `}` 配平结束为止)。 */
function rootBlock(): string {
  const start = css.indexOf(':root {')
  expect(start).toBeGreaterThan(-1)
  let depth = 0
  for (let i = start; i < css.length; i++) {
    if (css[i] === '{') depth++
    if (css[i] === '}') {
      depth--
      if (depth === 0) return css.slice(start, i + 1)
    }
  }
  throw new Error(':root 块未配平')
}

describe('冷白画布值层', () => {
  const root = rootBlock()

  it('画布底色是冷灰白 #EEF0F2', () => {
    expect(root).toMatch(/--background:\s*#EEF0F2/i)
    expect(root).toMatch(/--surface-canvas:\s*#EEF0F2/i)
  })

  it('卡片是纯白 #FFFFFF', () => {
    expect(root).toMatch(/--card:\s*#FFFFFF/i)
    expect(root).toMatch(/--surface-card:\s*#FFFFFF/i)
  })

  it('主文字是冷蓝黑,不是暖棕黑', () => {
    expect(root).toMatch(/--foreground:\s*#11151C/i)
    expect(root).not.toMatch(/--foreground:\s*#1A1614/i)
  })

  it('次级面/边框/交互态全部冷灰化,无暖米色残留', () => {
    for (const stale of ['#FAF8F4', '#F3F0E9', '#E3DED2', '#EFEAE0', '#E8E4DA', '#5C544C', '#8C8378']) {
      expect(root, `残留暖色 ${stale}`).not.toContain(stale)
    }
  })

  it('选中态底色中性化,不带品牌橙', () => {
    expect(root).toMatch(/--interactive-selected:\s*#E2E6EB/i)
    expect(root).not.toMatch(/--interactive-selected:\s*rgba\(235,\s*129,\s*49/)
  })
})

describe('serif display 退场', () => {
  it('index.css 不再注册 --font-display', () => {
    expect(css).not.toContain('--font-display')
  })
})

describe('白卡无边界', () => {
  it('.kq-card 不挂 border 与 box-shadow', () => {
    const m = css.match(/\.kq-card\s*\{([^}]*)\}/)
    expect(m).not.toBeNull()
    const body = m![1]
    expect(body).not.toMatch(/border:\s*1px/)
    expect(body).not.toMatch(/box-shadow/)
    expect(body).toMatch(/background:\s*var\(--surface-card\)/)
  })
})
