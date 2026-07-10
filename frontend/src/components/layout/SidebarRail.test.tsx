import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { SidebarRail } from './SidebarRail'
import { useUiStore } from '@/stores/uiStore'

describe('SidebarRail', () => {
  it('渲染品牌 + 全部 nav 标签(展开态)', () => {
    render(
      <MemoryRouter initialEntries={['/']}>
        <SidebarRail />
      </MemoryRouter>,
    )
    expect(screen.getByText('KwikQuant')).toBeInTheDocument()
    // '交易' label span 含 PAPER badge,单独测;其余 label 精确匹配
    for (const label of ['主页', '策略工作台', '回测', '组合总览', '行情', '风控', '交易历史', '设置']) {
      expect(screen.getByText(label)).toBeInTheDocument()
    }
  })

  it('当前路径 / 时主页项 active(accent-soft)', () => {
    render(
      <MemoryRouter initialEntries={['/']}>
        <SidebarRail />
      </MemoryRouter>,
    )
    const homeLink = screen.getByText('主页').closest('a')!
    expect(homeLink.className).toContain('bg-accent-soft')
  })

  it('trade 项显示 PAPER(uiStore 默认 PAPER)', () => {
    useUiStore.setState({ tradeMode: 'PAPER' })
    render(
      <MemoryRouter initialEntries={['/trade']}>
        <SidebarRail />
      </MemoryRouter>,
    )
    expect(screen.getByText('PAPER')).toBeInTheDocument()
  })

  it('uiStore tradeMode=LIVE 时 trade 项显示 LIVE badge', () => {
    useUiStore.setState({ tradeMode: 'LIVE' })
    render(
      <MemoryRouter initialEntries={['/trade']}>
        <SidebarRail />
      </MemoryRouter>,
    )
    expect(screen.getByText('LIVE')).toBeInTheDocument()
  })
})
