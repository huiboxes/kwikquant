import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// 每个测试后卸载渲染树，避免跨用例 DOM 泄漏。
afterEach(() => {
  cleanup()
})
