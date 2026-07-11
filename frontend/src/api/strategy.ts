import { apiFetch } from '@/lib/http'
import type { components } from '@/types/api-gen'

/**
 * strategy typed client(list + stop + pause + start;其他端点留 StrategyPage 任务)。
 *
 * 端点(均 JWT):
 *  - GET  /api/v1/strategies        → StrategyDetailDto[](当前用户策略列表)
 *  - POST /api/v1/strategies/{id}/stop  → StrategyDetailDto(停止;RUNNING/PAUSED/ERROR→STOPPED)
 *  - POST /api/v1/strategies/{id}/pause → StrategyDetailDto(暂停;RUNNING→PAUSED)
 *  - POST /api/v1/strategies/{id}/start → StrategyDetailDto(启动;PAUSED→RUNNING)
 *
 * 其他端点(get/create/codes/publish/ready)留 StrategyPage 任务建,本任务不预建(YAGNI)。
 */
type StrategyDetailDto = components['schemas']['StrategyDetailDto']

/** 查询当前用户策略列表。 */
export function fetchStrategies(): Promise<StrategyDetailDto[]> {
  return apiFetch<StrategyDetailDto[]>('/api/v1/strategies')
}

/**
 * 停止单个策略(POST /stop)。
 * RUNNING/PAUSED/ERROR → STOPPED。状态不可转移返回 409(7002)。
 */
export function stopStrategy(id: number): Promise<StrategyDetailDto> {
  return apiFetch<StrategyDetailDto>(`/api/v1/strategies/${id}/stop`, { method: 'POST' })
}

/**
 * 暂停单个策略(POST /pause)。RUNNING → PAUSED。状态不可转移返回 409(7002)。
 * Dashboard 运行中策略卡"暂停"按钮用(StrategyPage 复用)。
 */
export function pauseStrategy(id: number): Promise<StrategyDetailDto> {
  return apiFetch<StrategyDetailDto>(`/api/v1/strategies/${id}/pause`, { method: 'POST' })
}

/**
 * 启动单个策略(POST /start)。PAUSED → RUNNING。状态不可转移返回 409(7002)。
 * Dashboard 暂停策略"启动"按钮用(StrategyPage 复用)。
 * 注:start 端点语义 PAUSED→RUNNING(对偶于 pause 的 RUNNING→PAUSED);若后端也支持
 * DRAFT/READY→RUNNING(发布即启动),Dashboard 只用 PAUSED→RUNNING 路径,不受影响。
 */
export function startStrategy(id: number): Promise<StrategyDetailDto> {
  return apiFetch<StrategyDetailDto>(`/api/v1/strategies/${id}/start`, { method: 'POST' })
}
