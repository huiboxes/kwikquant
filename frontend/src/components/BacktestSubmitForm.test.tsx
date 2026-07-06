import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { BacktestSubmitForm } from './BacktestSubmitForm'

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { mutations: { retry: false } },
  })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  return wrapper
}

describe('BacktestSubmitForm', () => {
  it('渲染所有字段 + 提交按钮', () => {
    render(<BacktestSubmitForm strategyId={1} />, { wrapper: createWrapper() })
    expect(screen.getByLabelText('交易对')).toBeInTheDocument()
    expect(screen.getByLabelText('交易所')).toBeInTheDocument()
    expect(screen.getByLabelText('K 线周期')).toBeInTheDocument()
    expect(screen.getByLabelText(/起始资金/)).toBeInTheDocument()
    expect(screen.getByLabelText('起始时间')).toBeInTheDocument()
    expect(screen.getByLabelText('结束时间')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '提交回测' })).toBeInTheDocument()
  })

  it('默认值预填(BTC/USDT, BINANCE, 1h, 10000)', () => {
    render(<BacktestSubmitForm strategyId={1} />, { wrapper: createWrapper() })
    const symbolInput = screen.getByLabelText('交易对') as HTMLInputElement
    expect(symbolInput.value).toBe('BTC/USDT')
    const capitalInput = screen.getByLabelText(/起始资金/) as HTMLInputElement
    expect(capitalInput.value).toBe('10000')
  })

  it('交易所 select 含 3 个选项(BINANCE/OKX/BITGET,匹配后端 Exchange enum)', () => {
    render(<BacktestSubmitForm strategyId={1} />, { wrapper: createWrapper() })
    const select = screen.getByLabelText('交易所') as HTMLSelectElement
    expect(select.options.length).toBe(3)
  })

  it('K 线周期 select 含 7 个选项', () => {
    render(<BacktestSubmitForm strategyId={1} />, { wrapper: createWrapper() })
    const select = screen.getByLabelText('K 线周期') as HTMLSelectElement
    expect(select.options.length).toBe(7)
  })

  it('提交成功 → onSubmitted(taskId, input) 传 input 含 parameters', async () => {
    const onSubmitted = vi.fn()
    render(
      <BacktestSubmitForm strategyId={1} onSubmitted={onSubmitted} />,
      { wrapper: createWrapper() },
    )
    // datetime-local defaultValues 空,需填值(zod refine 比较 startTime<endTime)
    fireEvent.change(screen.getByLabelText('起始时间'), {
      target: { value: '2026-06-01T00:00' },
    })
    fireEvent.change(screen.getByLabelText('结束时间'), {
      target: { value: '2026-07-01T00:00' },
    })
    fireEvent.click(screen.getByRole('button', { name: '提交回测' }))
    await waitFor(() => expect(onSubmitted).toHaveBeenCalledOnce())
    const [taskId, input] = onSubmitted.mock.calls[0]
    expect(taskId).toBeGreaterThan(0)
    expect(input.parameters).toEqual({ initial_capital: 10000 })
    // startTime/endTime 转 ISO-8601 UTC(不假设测试时区,只验格式 + startTime<endTime)
    expect(input.startTime).toMatch(/^\d{4}-\d{2}-\d{2}T.*Z$/)
    expect(input.endTime).toMatch(/^\d{4}-\d{2}-\d{2}T.*Z$/)
    expect(input.startTime < input.endTime).toBe(true)
  })
})
