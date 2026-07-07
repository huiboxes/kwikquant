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

  it('未发布显保存/发布按钮', () => {
    render(<EditorZone {...props} />, { wrapper: wrap })
    expect(screen.getByText('保存')).toBeInTheDocument()
    expect(screen.getByText('发布')).toBeInTheDocument()
  })

  it('已发布显已发布(ghost)', () => {
    render(<EditorZone {...props} isPublished={true} />, { wrapper: wrap })
    expect(screen.getByText('已发布')).toBeInTheDocument()
  })
})
