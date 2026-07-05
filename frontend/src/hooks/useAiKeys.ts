import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '@/lib/http'
import type { components } from '@/types/api-gen'

export type LlmApiKeyView = components['schemas']['LlmApiKeyView']

/**
 * useAiKeys — LLM key 列表 query(spec §5 step 14)。
 * cache key: ['ai-keys']。为空时 AISidebar 显示 LLMKeyInlineDialog 配置入口。
 */
export function useAiKeys() {
  return useQuery({
    queryKey: ['ai-keys'],
    queryFn: () => apiFetch<LlmApiKeyView[]>('/api/v1/ai/keys'),
  })
}
