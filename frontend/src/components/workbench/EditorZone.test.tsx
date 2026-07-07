import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { EditorZone } from './EditorZone'

vi.mock('./TabBar', () => ({ TabBar: () => <div data-testid="tabbar" /> }))
vi.mock('./BottomControlBar', () => ({
  BottomControlBar: (props: { strategyId: number }) => (
    <div data-testid="bottom">{props.strategyId}</div>
  ),
}))
vi.mock('@/components/MonacoEditor', () => ({
  MonacoEditor: ({ value }: { value: string }) => (
    <div data-testid="monaco">{value}</div>
  ),
}))

const wrap = ({ children }: { children: React.ReactNode }) => (
  <MemoryRouter>{children}</MemoryRouter>
)

describe('EditorZone', () => {
  const props = {
    strategyId: 1,
    codeId: 1,
    source: 'print(1)',
    isPublished: false,
    onSourceChange: vi.fn(),
    onSave: vi.fn(),
    onPublish: vi.fn(),
    onRunBacktest: vi.fn(),
    onRunLive: vi.fn(),
    isSubmitting: false,
    isSaving: false,
    isPublishing: false,
  }

  it('渲染 TabBar + Monaco + BottomControlBar', () => {
    render(<EditorZone {...props} />, { wrapper: wrap })
    expect(screen.getByTestId('tabbar')).toBeInTheDocument()
    expect(screen.getByTestId('monaco')).toBeInTheDocument()
    expect(screen.getByTestId('bottom')).toBeInTheDocument()
  })

  it('Monaco 显 source', () => {
    render(<EditorZone {...props} />, { wrapper: wrap })
    expect(screen.getByTestId('monaco')).toHaveTextContent('print(1)')
  })
})
