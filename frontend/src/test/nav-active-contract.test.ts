/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

// DESIGN.md nav-active 契约：平底 interactive-selected + text-primary + rounded.sm。
// 选中态只靠底色表达，契约里没有额外指示条；这里锁死 CSS 防复发。
const css = readFileSync(resolve(dirname(fileURLToPath(import.meta.url)), '../index.css'), 'utf8')

describe('nav-active 契约', () => {
  it('选中态不附加契约外指示条', () => {
    expect(css).not.toMatch(/\.kq-nav-item\.active::(before|after)/)
  })

  it('选中态底色走 interactive-selected', () => {
    expect(css).toMatch(/\.kq-nav-item\.active\s*\{[^}]*var\(--interactive-selected\)/)
  })
})
