import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { SidebarRail } from './SidebarRail'

beforeEach(() => localStorage.clear())

const wrap = ({ children }: { children: React.ReactNode }) => (
  <MemoryRouter>{children}</MemoryRouter>
)

describe('SidebarRail 折叠', () => {
  it('默认展开(显文字 label)', () => {
    render(<SidebarRail />, { wrapper: wrap })
    expect(screen.getByText('总览')).toBeInTheDocument()
    expect(screen.getByText('策略工作台')).toBeInTheDocument()
  })

  it('点切换按钮收起(藏文字)', () => {
    render(<SidebarRail />, { wrapper: wrap })
    fireEvent.click(screen.getByRole('button', { name: /展开|收起/ }))
    expect(screen.queryByText('总览')).not.toBeInTheDocument()
    expect(screen.queryByText('策略工作台')).not.toBeInTheDocument()
  })

  it('收起态 localStorage 持久化', () => {
    render(<SidebarRail />, { wrapper: wrap })
    fireEvent.click(screen.getByRole('button', { name: /展开|收起/ }))
    expect(localStorage.getItem('kwikquant.sidebar.collapsed')).toBe('true')
  })

  it('再点展开恢复文字 + localStorage false', () => {
    render(<SidebarRail />, { wrapper: wrap })
    fireEvent.click(screen.getByRole('button', { name: /展开|收起/ })) // 收起
    fireEvent.click(screen.getByRole('button', { name: /展开|收起/ })) // 展开
    expect(screen.getByText('总览')).toBeInTheDocument()
    expect(localStorage.getItem('kwikquant.sidebar.collapsed')).toBe('false')
  })

  it('无 Risk 项(已删)', () => {
    render(<SidebarRail />, { wrapper: wrap })
    expect(screen.queryByText('Risk')).not.toBeInTheDocument()
  })
})
