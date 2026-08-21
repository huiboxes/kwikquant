import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { fetchTemplates, fetchTemplateDetail, forkTemplate } from '@/api/template'
import { templateKeys, strategyKeys, backtestKeys } from '@/api/_queryKeys'

/** 官方模板列表(模板库页)。 */
export function useTemplates() {
  return useQuery({
    queryKey: templateKeys.list(),
    queryFn: fetchTemplates,
  })
}

/** 模板详情(含源码；key=null 时禁用，详情 dialog 打开才拉)。 */
export function useTemplateDetail(key: string | null) {
  return useQuery({
    queryKey: templateKeys.detail(key ?? ''),
    queryFn: () => fetchTemplateDetail(key as string),
    enabled: key != null,
  })
}

/**
 * fork 模板为我的策略。成功后失效策略列表(fork 产生新策略)与回测列表
 * (best-effort 首回测可能已提交任务)。不做乐观更新——fork 是创建链路，
 * 新策略 id 由后端分配，成功后直接跳策略工作台深链选中。
 */
export function useForkTemplate() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: forkTemplate,
    onSettled: () => {
      qc.invalidateQueries({ queryKey: strategyKeys.all })
      qc.invalidateQueries({ queryKey: backtestKeys.all })
    },
  })
}
