import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { applyColorScheme, hydrateTheme, useThemeStore } from './themeStore'

describe('themeStore', () => {
  beforeEach(() => {
    // 重置 store 到默认（persist 中间件的 rehydrate 每个测试都要清）
    useThemeStore.setState({ colorScheme: 'dark' })
    localStorage.clear()
    document.documentElement.classList.remove('dark')
  })

  afterEach(() => {
    localStorage.clear()
    document.documentElement.classList.remove('dark')
  })

  describe('default state', () => {
    it('默认 colorScheme = dark', () => {
      expect(useThemeStore.getState().colorScheme).toBe('dark')
    })
  })

  describe('applyColorScheme (DOM 副作用)', () => {
    it("scheme=dark → <html> 加 .dark class", () => {
      applyColorScheme('dark')
      expect(document.documentElement.classList.contains('dark')).toBe(true)
    })
    it('scheme=light → <html> 去掉 .dark class', () => {
      document.documentElement.classList.add('dark')
      applyColorScheme('light')
      expect(document.documentElement.classList.contains('dark')).toBe(false)
    })
    it('重复调用同一 scheme 是幂等的', () => {
      applyColorScheme('dark')
      applyColorScheme('dark')
      expect(document.documentElement.classList.contains('dark')).toBe(true)
    })
  })

  describe('setColorScheme (state + 副作用)', () => {
    it('setColorScheme(light) → state 改 + DOM 去 .dark', () => {
      document.documentElement.classList.add('dark') // 模拟初始暗态
      useThemeStore.getState().setColorScheme('light')
      expect(useThemeStore.getState().colorScheme).toBe('light')
      expect(document.documentElement.classList.contains('dark')).toBe(false)
    })
    it('setColorScheme(dark) → state 改 + DOM 加 .dark', () => {
      useThemeStore.getState().setColorScheme('light')
      useThemeStore.getState().setColorScheme('dark')
      expect(useThemeStore.getState().colorScheme).toBe('dark')
      expect(document.documentElement.classList.contains('dark')).toBe(true)
    })
  })

  describe('toggleColorScheme', () => {
    it('dark → light', () => {
      useThemeStore.setState({ colorScheme: 'dark' })
      useThemeStore.getState().toggleColorScheme()
      expect(useThemeStore.getState().colorScheme).toBe('light')
    })
    it('light → dark', () => {
      useThemeStore.setState({ colorScheme: 'light' })
      useThemeStore.getState().toggleColorScheme()
      expect(useThemeStore.getState().colorScheme).toBe('dark')
    })
    it('toggle 也触发 DOM 同步', () => {
      useThemeStore.setState({ colorScheme: 'dark' })
      document.documentElement.classList.add('dark')
      useThemeStore.getState().toggleColorScheme()
      expect(document.documentElement.classList.contains('dark')).toBe(false)
    })
  })

  describe('hydrateTheme', () => {
    it('把 store 当前 state 应用到 DOM', () => {
      useThemeStore.setState({ colorScheme: 'light' })
      document.documentElement.classList.add('dark') // 假设 index.html 已挂 dark
      hydrateTheme()
      expect(document.documentElement.classList.contains('dark')).toBe(false)
    })
  })

  describe('persist (localStorage)', () => {
    it('setColorScheme 后 localStorage 有 kwikquant-theme key', () => {
      useThemeStore.getState().setColorScheme('light')
      const stored = localStorage.getItem('kwikquant-theme')
      expect(stored).not.toBeNull()
      expect(stored).toContain('light')
    })
  })
})
