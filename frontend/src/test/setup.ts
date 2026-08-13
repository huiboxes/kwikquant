import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, afterAll, beforeAll } from 'vitest'
import { server } from './server'

// jsdom polyfills(浏览器原生有,jsdom 无;cmdk/radix 等库需要)
class ResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver = ResizeObserver as never
// jsdom 无 Element.scrollIntoView(cmdk 滚动选中项用) + scrollTo(assistant-ui Thread Viewport 自动滚动用)
if (!Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = () => {}
}
if (!Element.prototype.scrollTo) {
  Element.prototype.scrollTo = function () {} as never
}
// jsdom 无 PointerEvent capture API(Radix Select/Popover 用 userEvent.click 触发真实
// pointerdown 时会调 target.hasPointerCapture,jsdom 未实现 → TypeError)
if (!Element.prototype.hasPointerCapture) {
  Element.prototype.hasPointerCapture = () => false
}
if (!Element.prototype.setPointerCapture) {
  Element.prototype.setPointerCapture = () => {}
}
if (!Element.prototype.releasePointerCapture) {
  Element.prototype.releasePointerCapture = () => {}
}
if (!globalThis.matchMedia) {
  globalThis.matchMedia = ((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  })) as never
}

// MSW:测试前启动 server,每用例后重置 handler(防 spy handler 泄漏)+ 清 WS 单例,全部结束关闭。
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(async () => {
  server.resetHandlers()
  // dynamic import 避免顶层 import ConnectionManager(会提前加载 @stomp/stompjs 真 Client,
  // 破坏 ConnectionManager.test 的 vi.mock)。afterEach 内加载走 mock 版,清 WS 单例防泄漏(M-6)。
  const { resetWsConnection } = await import('@/lib/ws/ConnectionManager')
  resetWsConnection()
  const { useMarketStore } = await import('@/stores/marketStore')
  useMarketStore.getState().clearTicks()
  cleanup()
})
afterAll(() => server.close())
