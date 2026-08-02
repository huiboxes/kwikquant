import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { downloadEquityPng } from '@/pages/backtest/pngExport'

describe('downloadEquityPng', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  beforeEach(() => {
    // mock Image:src setter 触发 onload(模拟加载完成)
    vi.stubGlobal(
      'Image',
      class MockImage {
        private _src = ''
        onload: (() => void) | null = null
        onerror: (() => void) | null = null
        set src(v: string) {
          this._src = v
          setTimeout(() => this.onload?.(), 0)
        }
        get src() {
          return this._src
        }
      },
    )
    // mock canvas + anchor(避开 jsdom 无 canvas getContext/toDataURL)。URL 用原生 jsdom 支持。
    const ctx = {
      drawImage: vi.fn(),
      scale: vi.fn(),
      fillText: vi.fn(),
      fillRect: vi.fn(),
    } as unknown as CanvasRenderingContext2D
    const canvas = {
      width: 0,
      height: 0,
      getContext: () => ctx,
      toDataURL: vi.fn(() => 'data:image/png;base64,xxx'),
    } as unknown as HTMLCanvasElement
    const anchor = { href: '', download: '', click: vi.fn() } as unknown as HTMLAnchorElement
    const origCreate = document.createElement.bind(document)
    vi.spyOn(document, 'createElement').mockImplementation((tag: string) => {
      if (tag === 'canvas') return canvas
      if (tag === 'a') return anchor
      return origCreate(tag)
    })
  })

  it('serializes svg and triggers download without crash', async () => {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg') as unknown as SVGSVGElement
    await expect(downloadEquityPng(svg, '回测-BTC-20260802.png')).resolves.toBeUndefined()
  })

  it('with meta: 绘制 banner 文字层不 crash + 仍触发下载', async () => {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg') as unknown as SVGSVGElement
    await expect(
      downloadEquityPng(svg, 'test.png', {
        strategyName: 'rsi-reversal',
        symbol: 'BTC/USDT',
        interval: '1h',
        range: '2026-01 → 2026-06',
        totalReturn: '+15.60%',
        totalReturnTone: 'up',
      }),
    ).resolves.toBeUndefined()
  })
})
