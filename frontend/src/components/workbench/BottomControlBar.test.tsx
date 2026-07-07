import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { BottomControlBar } from './BottomControlBar'

describe('BottomControlBar', () => {
  const props = {
    strategyId: 1,
    codeId: 1,
    isPublished: true,
    onSave: vi.fn(),
    onPublish: vi.fn(),
    isSaving: false,
    isPublishing: false,
    onRunBacktest: vi.fn(),
    onRunLive: vi.fn(),
    isSubmitting: false,
  }

  it('渲染交易对/interval/Backtest/Run Live', () => {
    render(<BottomControlBar {...props} />)
    expect(screen.getByText('Backtest')).toBeInTheDocument()
    expect(screen.getByText(/Run Live/)).toBeInTheDocument()
  })

  it('Run Live disabled 当未发布', () => {
    render(<BottomControlBar {...props} isPublished={false} />)
    expect(screen.getByText(/Run Live/).closest('button')).toBeDisabled()
  })

  it('点 Backtest 弹 AlertDialog 确认', async () => {
    render(<BottomControlBar {...props} />)
    fireEvent.click(screen.getByText('Backtest'))
    await waitFor(() =>
      expect(screen.getByText(/用所选参数跑回测/)).toBeInTheDocument(),
    )
  })

  it('AlertDialog 确认调 onRunBacktest', async () => {
    render(<BottomControlBar {...props} />)
    fireEvent.click(screen.getByText('Backtest'))
    await waitFor(() =>
      expect(screen.getByText(/用所选参数跑回测/)).toBeInTheDocument(),
    )
    fireEvent.click(screen.getByRole('button', { name: '确认' }))
    await waitFor(() => expect(props.onRunBacktest).toHaveBeenCalled())
  })

  it('渲染保存/发布按钮(最左侧)', () => {
    render(<BottomControlBar {...props} isPublished={false} />)
    expect(screen.getByText('保存')).toBeInTheDocument()
    expect(screen.getByText('发布')).toBeInTheDocument()
  })

  it('已发布显已发布', () => {
    render(<BottomControlBar {...props} isPublished={true} />)
    expect(screen.getByText('已发布')).toBeInTheDocument()
  })

  it('保存/发布 disabled 当无 codeId', () => {
    render(<BottomControlBar {...props} codeId={null} isPublished={false} />)
    expect(screen.getByText('保存').closest('button')).toBeDisabled()
    expect(screen.getByText('发布').closest('button')).toBeDisabled()
  })
})
