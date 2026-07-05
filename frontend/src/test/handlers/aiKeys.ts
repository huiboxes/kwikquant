import { HttpResponse, http } from 'msw'

/**
 * LLM key 端点 MSW handler(spec §5 step 14)。
 * GET /ai/keys 返 mock key 列表(批 1a 测试用,真实配置走后端 LlmApiKeyController)。
 */
const MOCK_KEYS = [
  {
    id: 1,
    label: '主 GPT key',
    provider: 'OPENAI' as const,
    apiKeyMasked: '...6xyz',
    baseUrl: '',
    createdAt: '2026-07-04T10:00:00Z',
  },
  {
    id: 2,
    label: 'Claude',
    provider: 'ANTHROPIC' as const,
    apiKeyMasked: '...9abc',
    baseUrl: '',
    createdAt: '2026-07-04T10:00:00Z',
  },
]

export const aiKeyHandlers = [
  http.get('/api/v1/ai/keys', () => {
    return HttpResponse.json({ code: 0, message: 'ok', data: MOCK_KEYS })
  }),
]
