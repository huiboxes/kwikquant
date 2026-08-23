import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { TradeModeToggle } from './TradeModeToggle'
import { useUiStore } from '@/stores/uiStore'

describe('TradeModeToggle', () => {
  it('PAPER 选中走 live-paper 语义色(accent-soft 底 + accent-warm 字)', () => {
    useUiStore.setState({ tradeMode: 'PAPER' })
    render(<TradeModeToggle />)
    const paper = screen.getByRole('radio', { name: '模拟' })
    expect(paper.className).toContain('bg-accent-soft')
    expect(paper.className).toContain('text-accent-warm')
    // 不再侵占 up 语义色
    expect(paper.className).not.toContain('bg-up/15')
  })

  it('LIVE 选中走 onyx 实底,与 PAPER 单一强区分', () => {
    useUiStore.setState({ tradeMode: 'LIVE', liveConfirmedThisSession: true })
    render(<TradeModeToggle />)
    const live = screen.getByRole('radio', { name: '实盘' })
    expect(live.className).toContain('bg-onyx')
  })
})
