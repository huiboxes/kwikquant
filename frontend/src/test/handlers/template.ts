import { http, HttpResponse } from 'msw'
import type { components } from '@/types/api-gen'
import { envelope } from './_envelope'

/**
 * strategy template MSW handlers(P1-3 官方模板库)。
 * fixture 3 个模板：趋势×2 + 均值回归×1，覆盖标签过滤路径。
 * fork 返固定新策略(id=900)+ firstBacktestTaskId=901(best-effort 成功分支);
 * 失败/降级分支测试用 server.use override。
 */
type TemplateDto = components['schemas']['TemplateDto']
type TemplateDetailDto = components['schemas']['TemplateDetailDto']
type TemplateForkResultDto = components['schemas']['TemplateForkResultDto']

export const TEMPLATE_FIXTURES: TemplateDto[] = [
  {
    key: 'ma-double-cross',
    name: '均线双金叉',
    description: 'MA5/MA10/MA20 双重确认金叉做多、死叉平仓。入门首选',
    tags: ['趋势跟踪'],
    symbol: 'BTC/USDT',
    exchange: 'OKX',
    intervalValue: '1h',
    backtestWindowDays: 90,
  },
  {
    key: 'fixed-grid',
    name: '固定网格',
    description: '围绕均线基线每 1% 一格，下穿买上穿卖',
    tags: ['网格', '均值回归'],
    symbol: 'BTC/USDT',
    exchange: 'OKX',
    intervalValue: '1h',
    backtestWindowDays: 90,
  },
  {
    key: 'rsi-reversal',
    name: 'RSI 超卖反转',
    description: 'RSI(14) 跌破 30 超卖做多、升破 70 超买平仓',
    tags: ['均值回归'],
    symbol: 'ETH/USDT',
    exchange: 'OKX',
    intervalValue: '1h',
    backtestWindowDays: 90,
  },
]

function detailOf(t: TemplateDto): TemplateDetailDto {
  return { ...t, parameters: '{}', sourceCode: `# ${t.key}\ndef on_bar(bar, ctx):\n    pass\n` }
}

export const templateHandlers = [
  // GET /api/v1/strategies/templates → 官方模板列表
  http.get('/api/v1/strategies/templates', () => {
    return HttpResponse.json(envelope(TEMPLATE_FIXTURES))
  }),

  // GET /api/v1/strategies/templates/{key} → 详情(含源码)；未知 key → 404/7008
  http.get('/api/v1/strategies/templates/:key', ({ params }) => {
    const t = TEMPLATE_FIXTURES.find((x) => x.key === params.key)
    if (!t) {
      return HttpResponse.json(envelope(null, 7008, `Template not found: ${params.key}`), { status: 404 })
    }
    return HttpResponse.json(envelope(detailOf(t)))
  }),

  // POST /api/v1/strategies/templates/{key}/fork → 新策略 + 首回测任务
  http.post('/api/v1/strategies/templates/:key/fork', ({ params }) => {
    const t = TEMPLATE_FIXTURES.find((x) => x.key === params.key)
    if (!t) {
      return HttpResponse.json(envelope(null, 7008, `Template not found: ${params.key}`), { status: 404 })
    }
    // fork 出的新策略:version/pnl/exchangeAccountId/stopReason 运行时为 null(真实语义),
    // 但 api-gen 未标 nullable(springdoc 限制)→ 同 handlers/risk.ts ruleResult 先例 cast。
    const result: TemplateForkResultDto = {
      strategy: {
        id: 900,
        name: t.name,
        description: t.description,
        symbol: t.symbol,
        exchange: t.exchange,
        marketType: 'SPOT',
        marginMode: null,
        leverage: null,
        intervalValue: t.intervalValue,
        status: 'DRAFT',
        parameters: '{}',
        createdAt: '2026-08-20T00:00:00Z',
        updatedAt: '2026-08-20T00:00:00Z',
        version: null as unknown as string,
        pnl: null as unknown as number,
        exchangeAccountId: null as unknown as number,
        stopReason: null as unknown as string,
      },
      firstBacktestTaskId: 901,
      backtestSkipReason: null,
    }
    return HttpResponse.json(envelope(result))
  }),
]
