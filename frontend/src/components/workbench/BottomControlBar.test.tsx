import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { BottomControlBar } from './BottomControlBar'

describe('BottomControlBar', () => {
  const props = {
    strategyId: 1,
    isPublished: true,
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
})
