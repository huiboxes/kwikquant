import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { TopBar } from './TopBar'

vi.mock('@/hooks/useStrategy', () => ({
  useStrategy: (id: number | null) => ({
    data: id === 1 ? { id: 1, name: 'BTC 网格' } : undefined,
  }),
}))

vi.mock('@/components/WsConnectionIndicator', () => ({
  WsConnectionIndicator: () => <span aria-label="WebSocket 已连接">Live</span>,
}))

function wrap(initial: string) {
  return ({ children }: { children: React.ReactNode }) => (
    <MemoryRouter initialEntries={[initial]}>{children}</MemoryRouter>
  )
}

describe('TopBar', () => {
  it('无 active 显根面包屑(kwikquant.io / workbench)', () => {
    render(<TopBar />, { wrapper: wrap('/workbench') })
    expect(screen.getByText('kwikquant.io')).toBeInTheDocument()
    expect(screen.getByText('workbench')).toBeInTheDocument()
  })

  it('有 active 显策略文件名', () => {
    render(<TopBar />, { wrapper: wrap('/workbench?tabs=1&active=1') })
    expect(screen.getByText('BTC 网格.py')).toBeInTheDocument()
  })

  it('渲染 WsConnectionIndicator(Live)', () => {
    render(<TopBar />, { wrapper: wrap('/workbench') })
    expect(screen.getByLabelText(/WebSocket/)).toBeInTheDocument()
  })
})
