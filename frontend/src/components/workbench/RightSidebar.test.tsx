import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { RightSidebar } from './RightSidebar'

vi.mock('@/components/AISidebar', () => ({
  AISidebar: () => <div data-testid="ai">AI</div>,
}))
vi.mock('./BacktestResultPanel', () => ({
  BacktestResultPanel: () => <div data-testid="panel">Panel</div>,
}))

beforeEach(() => localStorage.clear())

const wrap = ({ children }: { children: React.ReactNode }) => (
  <MemoryRouter>{children}</MemoryRouter>
)

describe('RightSidebar', () => {
  it('展开态渲染 BacktestResultPanel + AISidebar', () => {
    render(<RightSidebar strategyId={1} taskId={null} />, { wrapper: wrap })
    expect(screen.getByTestId('panel')).toBeInTheDocument()
    expect(screen.getByTestId('ai')).toBeInTheDocument()
  })

  it('点收起按钮折叠(藏 panel+ai,显展开按钮)', () => {
    render(<RightSidebar strategyId={1} taskId={null} />, { wrapper: wrap })
    fireEvent.click(screen.getByRole('button', { name: '收起右栏' }))
    expect(screen.queryByTestId('panel')).not.toBeInTheDocument()
    expect(screen.queryByTestId('ai')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '展开右栏' })).toBeInTheDocument()
  })

  it('折叠态 localStorage 持久化', () => {
    render(<RightSidebar strategyId={1} taskId={null} />, { wrapper: wrap })
    fireEvent.click(screen.getByRole('button', { name: '收起右栏' }))
    expect(localStorage.getItem('kwikquant.rightsidebar.collapsed')).toBe('true')
  })

  it('折叠态点回测图标唤抽屉(显 panel)', () => {
    render(<RightSidebar strategyId={1} taskId={null} />, { wrapper: wrap })
    fireEvent.click(screen.getByRole('button', { name: '收起右栏' }))
    fireEvent.click(screen.getByRole('button', { name: '回测结果' }))
    expect(screen.getByTestId('panel')).toBeInTheDocument()
  })

  it('折叠态点 AI 图标唤抽屉(显 ai)', () => {
    render(<RightSidebar strategyId={1} taskId={null} />, { wrapper: wrap })
    fireEvent.click(screen.getByRole('button', { name: '收起右栏' }))
    fireEvent.click(screen.getByRole('button', { name: 'AI 助手' }))
    expect(screen.getByTestId('ai')).toBeInTheDocument()
  })
})
