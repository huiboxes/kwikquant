import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { TabBar } from './TabBar'

vi.mock('@/hooks/useStrategy', () => ({
  useStrategy: (id: number | null) => ({
    data: id ? { id, name: `s${id}` } : undefined,
  }),
}))

const wrap = (initial = '/workbench?tabs=1,2&active=1') =>
  ({ children }: { children: React.ReactNode }) => (
    <MemoryRouter initialEntries={[initial]}>{children}</MemoryRouter>
  )

describe('TabBar', () => {
  it('渲染每个 tab 的文件名({name}.py)', () => {
    render(<TabBar />, { wrapper: wrap() })
    expect(screen.getByText('s1.py')).toBeInTheDocument()
    expect(screen.getByText('s2.py')).toBeInTheDocument()
  })

  it('末尾 + 按钮跳 /strategies', () => {
    render(<TabBar />, { wrapper: wrap() })
    expect(screen.getByRole('link', { name: '新建策略' })).toHaveAttribute(
      'href',
      '/strategies',
    )
  })

  it('点 tab 切 active(aria-selected)', () => {
    render(<TabBar />, { wrapper: wrap() })
    expect(screen.getByRole('tab', { name: /s1\.py/ })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    fireEvent.click(screen.getByText('s2.py'))
    expect(screen.getByRole('tab', { name: /s2\.py/ })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    expect(screen.getByRole('tab', { name: /s1\.py/ })).toHaveAttribute(
      'aria-selected',
      'false',
    )
  })

  it('关闭 X 按钮(每个 tab 一个)', () => {
    render(<TabBar />, { wrapper: wrap() })
    const closes = screen.getAllByRole('button', { name: /关闭/ })
    expect(closes).toHaveLength(2)
  })
})
