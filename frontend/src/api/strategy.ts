import { apiFetch } from '@/lib/http'
import type { components } from '@/types/api-gen'

/**
 * strategy typed client(list + detail + codes + publish + ready + stop/pause/start;StrategyPage 用)。
 *
 * 端点(均 JWT):
 *  - GET  /api/v1/strategies                                → StrategyDetailDto[](列表)
 *  - GET  /api/v1/strategies/{id}                          → StrategyDetailDto(详情)
 *  - GET  /api/v1/strategies/{strategyId}/codes            → StrategyCodeDto[](版本列表,无 sourceCode,按版本号倒序)
 *  - GET  /api/v1/strategies/{strategyId}/codes/{codeId}   → StrategyCodeDetailDto(含 sourceCode,Monaco 加载用)
 *  - POST /api/v1/strategies/{strategyId}/codes            body CreateCodeRequest → StrategyCodeDto(DRAFT,新建草稿)
 *  - PUT  /api/v1/strategies/{strategyId}/codes/{codeId}  body CreateCodeRequest → StrategyCodeDetailDto(仅 DRAFT 可改,自动保存)
 *  - POST /api/v1/strategies/{strategyId}/codes/{codeId}/publish → StrategyCodeDto(DRAFT→PUBLISHED 冻结)
 *  - POST /api/v1/strategies/{id}/ready                    → StrategyDetailDto(DRAFT→READY)
 *  - POST /api/v1/strategies/{id}/stop                     → StrategyDetailDto(RUNNING/PAUSED/ERROR→STOPPED)
 *  - POST /api/v1/strategies/{id}/pause                    → StrategyDetailDto(RUNNING→PAUSED)
 *  - POST /api/v1/strategies/{id}/start                    → StrategyDetailDto(READY→RUNNING;前端也用于 PAUSED→RUNNING resume,契约描述只说 READY,TD-033)
 *
 * honest:
 *  - StrategyDetailDto 无 version/pnl/lines 字段:version 从 codes list[0].versionNumber 派生;
 *    lines 从 codeDetail.sourceCode.split('\n').length 派生;pnl 无端点(running PnL)占位 "—" TD-036。
 *  - start 契约描述只说 READY→RUNNING,但前端(DashboardPage/StrategyPage)也用于 PAUSED→RUNNING
 *    (resume 语义,无独立 resume 端点)。TD-033 待后端澄清。
 */
type StrategyDetailDto = components['schemas']['StrategyDetailDto']
type StrategyCodeDto = components['schemas']['StrategyCodeDto']
type StrategyCodeDetailDto = components['schemas']['StrategyCodeDetailDto']
type CreateCodeRequest = components['schemas']['CreateCodeRequest']

export type {
  StrategyDetailDto,
  StrategyCodeDto,
  StrategyCodeDetailDto,
  CreateCodeRequest,
}

/** 策略状态枚举(契约 api-gen;6 态)。 */
export type StrategyStatus = StrategyDetailDto['status']
/** 代码版本状态枚举(DRAFT|PUBLISHED|ARCHIVED 3 态)。 */
export type StrategyCodeStatus = StrategyCodeDto['status']

/** 查询当前用户策略列表。 */
export function fetchStrategies(): Promise<StrategyDetailDto[]> {
  return apiFetch<StrategyDetailDto[]>('/api/v1/strategies')
}

/** 查策略详情(Header 信息源)。 */
export function fetchStrategyDetail(id: number): Promise<StrategyDetailDto> {
  return apiFetch<StrategyDetailDto>(`/api/v1/strategies/${id}`)
}

/** 查代码版本列表(按版本号倒序,无 sourceCode;版本 modal + 派生 version 用)。 */
export function fetchStrategyCodes(strategyId: number): Promise<StrategyCodeDto[]> {
  return apiFetch<StrategyCodeDto[]>(`/api/v1/strategies/${strategyId}/codes`)
}

/** 查代码版本详情(含 sourceCode;Monaco 加载草稿用)。 */
export function fetchStrategyCodeDetail(
  strategyId: number,
  codeId: number,
): Promise<StrategyCodeDetailDto> {
  return apiFetch<StrategyCodeDetailDto>(
    `/api/v1/strategies/${strategyId}/codes/${codeId}`,
  )
}

/** 新建代码草稿(POST /codes;DRAFT,已有未发布 DRAFT 返 409 7005)。 */
export function createCodeDraft(
  strategyId: number,
  req: CreateCodeRequest,
): Promise<StrategyCodeDto> {
  return apiFetch<StrategyCodeDto>(`/api/v1/strategies/${strategyId}/codes`, {
    method: 'POST',
    body: req,
  })
}

/** 更新代码草稿(PUT /codes/{codeId};仅 DRAFT 可改,发布后冻结,非 DRAFT 返 409 7005)。Monaco 自动保存用。 */
export function updateCodeDraft(
  strategyId: number,
  codeId: number,
  req: CreateCodeRequest,
): Promise<StrategyCodeDetailDto> {
  return apiFetch<StrategyCodeDetailDto>(
    `/api/v1/strategies/${strategyId}/codes/${codeId}`,
    { method: 'PUT', body: req },
  )
}

/** 发布代码版本(POST /publish;DRAFT→PUBLISHED 冻结,新版本走新 codeId)。发布 modal 用。 */
export function publishCode(strategyId: number, codeId: number): Promise<StrategyCodeDto> {
  return apiFetch<StrategyCodeDto>(
    `/api/v1/strategies/${strategyId}/codes/${codeId}/publish`,
    { method: 'POST' },
  )
}

/** 标记策略就绪(POST /ready;DRAFT→READY,需有发布代码,无返 409 7006)。发布后调用。 */
export function readyStrategy(id: number): Promise<StrategyDetailDto> {
  return apiFetch<StrategyDetailDto>(`/api/v1/strategies/${id}/ready`, { method: 'POST' })
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
 * 启动单个策略(POST /start)。READY → RUNNING(契约);前端也用于 PAUSED→RUNNING resume(TD-033)。
 * 状态不可转移返回 409(7002);Worker 启动失败返回 500(7200)。
 */
export function startStrategy(id: number): Promise<StrategyDetailDto> {
  return apiFetch<StrategyDetailDto>(`/api/v1/strategies/${id}/start`, { method: 'POST' })
}
