import { apiFetch } from '@/lib/http'
import type { components } from '@/types/api-gen'

/**
 * strategy template typed client(官方模板库:官方模板库)。
 *
 * 端点(均 JWT):
 *  - GET  /api/v1/strategies/templates              → TemplateDto[](元数据，不含源码)
 *  - GET  /api/v1/strategies/templates/{key}        → TemplateDetailDto(含 sourceCode/parameters)
 *  - POST /api/v1/strategies/templates/{key}/fork   → TemplateForkResultDto
 *
 * fork 语义：复制模板为当前用户 READY 策略（源码已发布，可直接启动），best-effort 提交首次回测
 * (模板推荐窗口)；回测提交失败不回滚 fork,firstBacktestTaskId=null + backtestSkipReason。
 * 模板 key 不存在返回 404(7008 TEMPLATE_NOT_FOUND)。
 */
type TemplateDto = components['schemas']['TemplateDto']
type TemplateDetailDto = components['schemas']['TemplateDetailDto']
type TemplateForkResultDto = components['schemas']['TemplateForkResultDto']

export type { TemplateDto, TemplateDetailDto, TemplateForkResultDto }

/** 官方模板列表(目录顺序，入门款在前)。 */
export function fetchTemplates(): Promise<TemplateDto[]> {
  return apiFetch<TemplateDto[]>('/api/v1/strategies/templates')
}

/** 模板详情(含源码，代码预览用)。 */
export function fetchTemplateDetail(key: string): Promise<TemplateDetailDto> {
  return apiFetch<TemplateDetailDto>(`/api/v1/strategies/templates/${encodeURIComponent(key)}`)
}

/** fork 模板为我的策略(后端自动发布源码 + best-effort 首回测)。 */
export function forkTemplate(key: string): Promise<TemplateForkResultDto> {
  return apiFetch<TemplateForkResultDto>(
    `/api/v1/strategies/templates/${encodeURIComponent(key)}/fork`,
    { method: 'POST' },
  )
}
