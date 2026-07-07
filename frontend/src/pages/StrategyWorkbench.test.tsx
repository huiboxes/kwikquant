import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { StrategyWorkbench } from './StrategyWorkbench'

vi.mock('@/hooks/useStrategyCode', () => ({
  useStrategyCodes: () => ({ data: [{ id: 1, status: 'DRAFT' }] }),
  useStrategyCode: () => ({ data: { sourceCode: 'print(1)' } }),
}))
vi.mock('@/hooks/usePublishCode', () => ({
  usePublishCode: () => ({ mutate: vi.fn(), isPending: false }),
}))
vi.mock('@/hooks/useUpdateDraftCode', () => ({
  useUpdateDraftCode: () => ({ mutate: vi.fn(), isPending: false }),
}))
vi.mock('@/hooks/useSubmitBacktest', () => ({
  useSubmitBacktest: () => ({ mutate: vi.fn(), isPending: false }),
}))
vi.mock('@/components/workbench/EditorZone', () => ({
  EditorZone: () => <div data-testid="editor" />,
}))
vi.mock('@/components/workbench/RightSidebar', () => ({
  RightSidebar: () => <div data-testid="right" />,
}))

const wrap = (initial: string) =>
  ({ children }: { children: React.ReactNode }) => (
    <MemoryRouter initialEntries={[initial]}>{children}</MemoryRouter>
  )

describe('StrategyWorkbench', () => {
  it('无 tabs 显空态(从策略列表选一个策略)', () => {
    render(<StrategyWorkbench />, { wrapper: wrap('/workbench') })
    expect(screen.getByText(/从策略列表选一个策略/)).toBeInTheDocument()
  })

  it('有 tabs 渲染 EditorZone + RightSidebar', () => {
    render(<StrategyWorkbench />, { wrapper: wrap('/workbench?tabs=1&active=1') })
    expect(screen.getByTestId('editor')).toBeInTheDocument()
    expect(screen.getByTestId('right')).toBeInTheDocument()
  })
})
