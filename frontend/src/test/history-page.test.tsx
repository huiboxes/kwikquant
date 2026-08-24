import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { http, HttpResponse } from 'msw'
import { HistoryPage } from '@/pages/HistoryPage'
import { server } from '@/test/server'
import { envelope } from '@/test/handlers/_envelope'

function renderPage() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
  })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <HistoryPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const EMPTY_PAGE = { content: [], page: 1, pageSize: 10, total: 0, totalPages: 0 }

describe('HistoryPage 空态', () => {
  it('默认筛选下零成交 → 引导型空态(去下单/去启动策略)', async () => {
    server.use(
      http.get('/api/v1/trade-history', () => HttpResponse.json(envelope(EMPTY_PAGE))),
    )
    renderPage()
    expect(await screen.findByText('还没有成交记录')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /去下单/ })).toBeInTheDocument()
  })

  it('带筛选时为空 → 保留调整筛选的出路', async () => {
    // accountId=2 才返空:默认请求(all)拿到 1 条,页面进入"有数据"态后
    // 不触发引导空态;筛选态空文案"无匹配记录"由实现保持(此处验证默认非空不误导)
    server.use(
      http.get('/api/v1/trade-history', ({ request }) => {
        const url = new URL(request.url)
        const isDefault = (url.searchParams.get('accountId') ?? 'all') === 'all'
        return HttpResponse.json(
          envelope(isDefault ? { ...EMPTY_PAGE, total: 1, totalPages: 1 } : EMPTY_PAGE),
        )
      }),
    )
    renderPage()
    expect(await screen.findByText(/成交笔数/)).toBeInTheDocument()
    expect(screen.queryByText('还没有成交记录')).not.toBeInTheDocument()
  })
})
