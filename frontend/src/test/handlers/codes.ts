import { HttpResponse, http } from 'msw'

/**
 * 策略代码端点 MSW handler(spec §5 step 15-16,契约 A)。
 *
 * GET    /strategies/:id/codes           → StrategyCodeDto[](无 sourceCode)
 * GET    /strategies/:id/codes/:codeId   → StrategyCodeDetailDto(含 sourceCode,契约 A)
 * POST   /strategies/:id/codes/:codeId/publish → 状态 DRAFT→PUBLISHED
 */

interface MockCode {
  id: number
  strategyId: number
  versionNumber: number
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'
  language: string
  changelog: string
  sourceCode: string
  createdAt: string
  updatedAt: string
}

const TEMPLATE = `# KwikQuant 策略(草稿)
from kwikquant import Strategy

class MyStrategy(Strategy):
    def on_bar(self, bar):
        pass
`

const CODES: MockCode[] = [
  {
    id: 1001,
    strategyId: 1,
    versionNumber: 1,
    status: 'PUBLISHED',
    language: 'python',
    changelog: '初始发布',
    sourceCode: TEMPLATE,
    createdAt: '2026-07-04T10:00:00Z',
    updatedAt: '2026-07-04T10:00:00Z',
  },
  {
    id: 1002,
    strategyId: 1,
    versionNumber: 2,
    status: 'DRAFT',
    language: 'python',
    changelog: '草稿编辑中',
    sourceCode: TEMPLATE,
    createdAt: '2026-07-05T10:00:00Z',
    updatedAt: '2026-07-05T10:00:00Z',
  },
]

function toDto(c: MockCode) {
  // list 用 DTO(无 sourceCode)
  const { sourceCode: _sp, ...dto } = c
  void _sp
  return dto
}

export const codeHandlers = [
  http.get('/api/v1/strategies/:id/codes', ({ params }) => {
    const sid = parseInt(params.id as string, 10)
    const list = CODES.filter((c) => c.strategyId === sid).map(toDto)
    return HttpResponse.json({ code: 0, message: 'ok', data: list })
  }),

  http.get('/api/v1/strategies/:id/codes/:codeId', ({ params }) => {
    const codeId = parseInt(params.codeId as string, 10)
    const c = CODES.find((x) => x.id === codeId)
    if (!c) {
      return HttpResponse.json(
        { code: 7004, message: '代码不存在', data: null },
        { status: 404 },
      )
    }
    return HttpResponse.json({ code: 0, message: 'ok', data: c })
  }),

  http.post('/api/v1/strategies/:id/codes/:codeId/publish', ({ params }) => {
    const codeId = parseInt(params.codeId as string, 10)
    const c = CODES.find((x) => x.id === codeId)
    if (!c) {
      return HttpResponse.json(
        { code: 7004, message: '代码不存在', data: null },
        { status: 404 },
      )
    }
    if (c.status === 'PUBLISHED') {
      return HttpResponse.json(
        { code: 7005, message: '代码已发布,不可重复发布', data: null },
        { status: 409 },
      )
    }
    c.status = 'PUBLISHED'
    c.updatedAt = '2026-07-05T14:00:00Z'
    return HttpResponse.json({ code: 0, message: 'ok', data: toDto(c) })
  }),
]
