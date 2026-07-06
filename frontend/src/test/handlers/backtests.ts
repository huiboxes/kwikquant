import { HttpResponse, http } from 'msw'
import type { BacktestTaskDto } from '@/hooks/useSubmitBacktest'

/**
 * backtests 端点 MSW handler(spec §5 step 19-20/23,批 1b 测试用)。
 *
 * POST /api/v1/backtests            → 提交,返 BacktestTaskDto(status=PENDING)
 * GET  /api/v1/backtests/:id        → 轮询,返 BacktestTaskDto(状态序列推进)
 * GET  /api/v1/backtests?strategyId → 列表(批 1b 暂不用,占位)
 *
 * 轮询序列(按 GET 次数推进):PENDING → RUNNING → COMPLETED(带 reportId)。
 * FAILED 用 /backtests/9999 触发(独立 task,首次 GET 即 FAILED)。
 *
 * 后端 ApiResponse envelope:{code:0, message, data}。
 */

type TaskStatus = BacktestTaskDto['status']

interface MockTask {
  id: number
  strategyId: number
  strategyCodeId: number
  status: TaskStatus
  symbol: string
  exchange: string
  intervalValue: string
  startTime: string
  endTime: string
  parameters: string
  result: string
  reportId: number | null
  errorMessage: string
  createdAt: string
  updatedAt: string
}

const NOW = '2026-07-04T12:00:00Z'

const TASKS = new Map<number, MockTask>()
const POLL_COUNTS = new Map<number, number>()
let nextId = 6002

function makeTask(id: number, status: TaskStatus, reportId: number | null): MockTask {
  return {
    id,
    strategyId: 1,
    strategyCodeId: 1001,
    status,
    symbol: 'BTC/USDT',
    exchange: 'BINANCE',
    intervalValue: '1h',
    startTime: '2026-06-01T00:00:00Z',
    endTime: '2026-07-01T00:00:00Z',
    parameters: JSON.stringify({ initial_capital: 10000 }),
    result: reportId !== null ? JSON.stringify({ realizedPnl: '523.18', tradeCount: 128 }) : '',
    reportId,
    errorMessage: '',
    createdAt: NOW,
    updatedAt: NOW,
  }
}

// 预置 fixture task 6001(测试用,轮询序列 PENDING→RUNNING→COMPLETED)
TASKS.set(6001, makeTask(6001, 'PENDING', null))
POLL_COUNTS.set(6001, 0)

/**
 * 重置模块级状态(测试间隔离,setup.ts afterEach 调)。
 * 防止同文件测试间 POLL_COUNTS 累积致顺序耦合(如 test2 GET 6001 借 test1 的 count)。
 */
export function resetBacktestState(): void {
  TASKS.clear()
  POLL_COUNTS.clear()
  nextId = 6002
  TASKS.set(6001, makeTask(6001, 'PENDING', null))
  POLL_COUNTS.set(6001, 0)
}

export const backtestHandlers = [
  http.post('/api/v1/backtests', async ({ request }) => {
    const body = (await request.json()) as {
      strategyId: number
      symbol: string
      exchange: string
      intervalValue: string
      startTime: string
      endTime: string
      parameters: string
    }
    const id = nextId++
    const task = makeTask(id, 'PENDING', null)
    task.strategyId = body.strategyId ?? 1
    task.symbol = body.symbol ?? task.symbol
    task.exchange = body.exchange ?? task.exchange
    task.intervalValue = body.intervalValue ?? task.intervalValue
    task.startTime = body.startTime ?? task.startTime
    task.endTime = body.endTime ?? task.endTime
    task.parameters = body.parameters ?? task.parameters
    TASKS.set(id, task)
    POLL_COUNTS.set(id, 0)
    return HttpResponse.json({ code: 0, message: 'ok', data: task }, { status: 201 })
  }),

  http.get('/api/v1/backtests/:id', ({ params }) => {
    const id = parseInt(params.id as string, 10)

    // 9999 = FAILED fixture(首次 GET 即 FAILED,errorMessage 填充)
    if (id === 9999) {
      const failed = makeTask(9999, 'FAILED', null)
      failed.errorMessage = '回测引擎异常:BacktestRunnerException(7300)'
      return HttpResponse.json({ code: 0, message: 'ok', data: failed })
    }

    const task = TASKS.get(id)
    if (!task) {
      return HttpResponse.json(
        { code: 7201, message: '回测任务不存在', data: null },
        { status: 404 },
      )
    }

    // 轮询序列推进:PENDING → RUNNING → COMPLETED(带 reportId=42)
    const count = (POLL_COUNTS.get(id) ?? 0) + 1
    POLL_COUNTS.set(id, count)

    if (count === 1) {
      task.status = 'RUNNING'
    } else if (count >= 2) {
      task.status = 'COMPLETED'
      task.reportId = 42
      task.result = JSON.stringify({ realizedPnl: '523.18', tradeCount: 128 })
    }
    task.updatedAt = NOW

    return HttpResponse.json({ code: 0, message: 'ok', data: task })
  }),

  http.get('/api/v1/backtests', () => {
    // 列表(批 1b 暂不用,返空)
    return HttpResponse.json({ code: 0, message: 'ok', data: [] })
  }),
]
