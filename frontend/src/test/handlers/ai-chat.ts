import { http, HttpResponse } from 'msw'
import type { components } from '@/types/api-gen'
import { envelope } from './_envelope'

/**
 * AI 会话历史 MSW handlers。mock GET/POST/DELETE /api/v1/strategies/{id}/ai/messages。
 * 内存存储(按 strategyId),test 间 reset 由 setup.ts afterEach resetHandlers 清。
 */
type AiChatMessageView = components['schemas']['AiChatMessageView']

const history: Record<number, AiChatMessageView[]> = {}

export const aiChatHandlers = [
  http.get('/api/v1/strategies/:id/ai/messages', ({ params }) => {
    const id = parseInt(String(params.id), 10)
    return HttpResponse.json(envelope(history[id] ?? []))
  }),
  http.post('/api/v1/strategies/:id/ai/messages', async ({ params, request }) => {
    const id = parseInt(String(params.id), 10)
    const body = (await request.json()) as { content: string; model: string }
    const msg: AiChatMessageView = {
      id: (history[id]?.length ?? 0) + 1,
      strategyId: id,
      role: 'ai',
      content: body.content,
      model: body.model,
      createdAt: '2026-07-28T00:00:00Z',
    }
    history[id] = [...(history[id] ?? []), msg]
    return HttpResponse.json(envelope(msg))
  }),
  http.delete('/api/v1/strategies/:id/ai/messages', ({ params }) => {
    const id = parseInt(String(params.id), 10)
    delete history[id]
    return HttpResponse.json(envelope(null))
  }),
]
