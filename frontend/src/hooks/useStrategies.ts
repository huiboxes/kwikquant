import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  fetchStrategies,
  fetchStrategyDetail,
  fetchStrategyCodes,
  fetchStrategyCodeDetail,
  createStrategy,
  createCodeDraft,
  deleteCodeDraft,
  updateCodeDraft,
  publishCode,
  readyStrategy,
  stopStrategy,
  pauseStrategy,
  startStrategy,
  deleteStrategy,
  fetchLastEditedStrategy,
} from '@/api/strategy'
import { strategyKeys } from '@/api/_queryKeys'
import type {
  StrategyDetailDto,
  StrategyCodeDto,
  StrategyCodeDetailDto,
  CreateCodeRequest,
  CreateStrategyRequest,
} from '@/api/strategy'

/**
 * useStrategies — 策略 list/detail/codes/publish/ready/stop/pause/start(StrategyPage + DashboardPage 用)。
 */

/** 查询当前用户策略列表(react-query)。list rail + Dashboard 运行中策略用。 */
export function useStrategies() {
  return useQuery({
    queryKey: strategyKeys.list(),
    queryFn: fetchStrategies,
  })
}

/** 查策略详情(Header 信息源)。StrategyPage 切换 selected 时 refetch。 */
export function useStrategyDetail(id: number | null) {
  return useQuery({
    queryKey: strategyKeys.detail(id ?? -1),
    queryFn: () => fetchStrategyDetail(id!),
    enabled: id != null,
  })
}

/** 查代码版本列表(按版本号倒序,无 sourceCode)。版本 modal + 派生 version 用。 */
export function useStrategyCodes(strategyId: number | null) {
  return useQuery({
    queryKey: strategyKeys.codes(strategyId ?? -1),
    queryFn: () => fetchStrategyCodes(strategyId!),
    enabled: strategyId != null,
  })
}

/** 查代码版本详情(含 sourceCode)。Monaco 加载草稿用。 */
export function useStrategyCodeDetail(strategyId: number | null, codeId: number | null) {
  return useQuery({
    queryKey: strategyKeys.codeDetail(strategyId ?? -1, codeId ?? -1),
    queryFn: () => fetchStrategyCodeDetail(strategyId!, codeId!),
    enabled: strategyId != null && codeId != null,
  })
}

/** 新建代码草稿(mutation;DRAFT,已有未发布 DRAFT 返 409 7005)。 */
export function useCreateCodeDraft() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ strategyId, req }: { strategyId: number; req: CreateCodeRequest }) =>
      createCodeDraft(strategyId, req),
    onSuccess: (data, { strategyId }) => {
      qc.invalidateQueries({ queryKey: strategyKeys.codes(strategyId) })
      // 新草稿 codeDetail(data.id)直接 setQueryData,避免 list 刷新前 codeDetail 空
      qc.setQueryData(strategyKeys.codeDetail(strategyId, data.id), data)
    },
  })
}

/** 删除代码草稿(mutation;仅 DRAFT 可删,非 DRAFT 返 409。成功后 invalidate codes + codeDetail)。WorkbenchTabBar × 用。 */
export function useDeleteCodeDraft() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ strategyId, codeId }: { strategyId: number; codeId: number }) =>
      deleteCodeDraft(strategyId, codeId),
    onSuccess: (_data, { strategyId, codeId }) => {
      qc.invalidateQueries({ queryKey: strategyKeys.codes(strategyId) })
      // 删的 codeDetail 移除(已删,refetch 会 404)
      qc.removeQueries({ queryKey: strategyKeys.codeDetail(strategyId, codeId) })
    },
  })
}

/** 创建策略(mutation;DRAFT,成功后 invalidate list + detail)。空 StrategyPage "创建策略"用。 */
export function useCreateStrategy() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (req: CreateStrategyRequest) => createStrategy(req),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: strategyKeys.list() })
      // 新策略 detail 即时写入(DRAFT),避免 selected 落到旧策略或 stale READY
      qc.setQueryData(strategyKeys.detail(data.id), data)
    },
  })
}

/** 更新代码草稿(mutation;仅 DRAFT 可改)。Monaco 自动保存用。 */
export function useUpdateCodeDraft() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({
      strategyId,
      codeId,
      req,
    }: {
      strategyId: number
      codeId: number
      req: CreateCodeRequest
    }) => updateCodeDraft(strategyId, codeId, req),
    onSuccess: (_data, { strategyId, codeId }) => {
      qc.invalidateQueries({ queryKey: strategyKeys.codeDetail(strategyId, codeId) })
      qc.invalidateQueries({ queryKey: strategyKeys.codes(strategyId) })
    },
  })
}

/** 发布代码版本(mutation;DRAFT→PUBLISHED 冻结)。发布 modal 用。 */
export function usePublishCode() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ strategyId, codeId }: { strategyId: number; codeId: number }) =>
      publishCode(strategyId, codeId),
    onSuccess: (data, { strategyId, codeId }) => {
      qc.invalidateQueries({ queryKey: strategyKeys.codes(strategyId) })
      // code.status DRAFT→PUBLISHED,invalidate codeDetail 防止 stale DRAFT 导致删草稿误判非草稿
      qc.invalidateQueries({ queryKey: strategyKeys.codeDetail(strategyId, codeId) })
      // data 是 publish 后的 PUBLISHED code,直接 setQueryData 即时刷新
      qc.setQueryData(strategyKeys.codeDetail(strategyId, codeId), data)
    },
  })
}

/** 标记策略就绪(mutation;DRAFT→READY)。发布后调用。 */
export function useReadyStrategy() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => readyStrategy(id),
    onSuccess: (_data, id) => {
      qc.invalidateQueries({ queryKey: strategyKeys.detail(id) })
      qc.invalidateQueries({ queryKey: strategyKeys.list() })
    },
  })
}

/**
 * useStopStrategy — 停止单个策略(POST /stop。RUNNING/PAUSED/ERROR→STOPPED)。
 * 单个停止用;紧急停止批量用 Promise.allSettled + 裸 stopStrategy(api 函数)。
 */
export function useStopStrategy() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => stopStrategy(id),
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: strategyKeys.all })
    },
  })
}

/**
 * usePauseStrategy — 暂停单个策略(POST /pause。RUNNING→PAUSED)。
 * Dashboard 运行中策略卡"暂停"按钮用(ConfirmDialog 后调)。
 */
export function usePauseStrategy() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => pauseStrategy(id),
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: strategyKeys.all })
    },
  })
}

/**
 * useStartStrategy — 启动单个策略(POST /start。READY→RUNNING;前端也用于 PAUSED→RUNNING resume TD-033)。
 */
export function useStartStrategy() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => startStrategy(id),
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: strategyKeys.all })
    },
  })
}

/**
 * useDeleteStrategy — 删除单个策略(DELETE /strategies/{id})。删整个策略实体含代码版本。
 * onSettled 刷新所有 strategy keys(list/detail/codes)。
 */
export function useDeleteStrategy() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => deleteStrategy(id),
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: strategyKeys.all })
    },
  })
}

/** 最近编辑的策略(Dashboard Hero 区"继续 xxx"引导用)。 */
export function useLastEditedStrategy() {
  return useQuery({
    queryKey: strategyKeys.lastEdited(),
    queryFn: fetchLastEditedStrategy,
  })
}

export type {
  StrategyDetailDto,
  StrategyCodeDto,
  StrategyCodeDetailDto,
  CreateCodeRequest,
}
