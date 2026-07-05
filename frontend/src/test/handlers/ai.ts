import { HttpResponse, http } from 'msw'

/**
 * AI chat SSE 端点 MSW handler(spec §5 step 8,六态)。
 * 正常 chunk / 4001 key 不存在 / 4003 key 非本人 / 8002 INVALID_PROVIDER / 8003 PROVIDER_ERROR / 401 token。
 *
 * 用 llmKeyId 区分六态(测试约定):
 *   9999 → 4001, 8888 → 4003, 8002 → 8002, 8003 → 8003, 1001 → 401, 其他 → 正常流
 */

function sseFrame(event: string, data: string): string {
  return `event: ${event}\ndata: ${data}\n\n`
}

export const aiHandlers = [
  http.post('/api/v1/ai/chat', async ({ request }) => {
    const body = (await request.json()) as { llmKeyId?: number }
    const keyId = body?.llmKeyId ?? 0

    if (keyId === 9999) {
      return HttpResponse.json(
        { code: 4001, message: 'LLM key 不存在', data: null },
        { status: 404 },
      )
    }
    if (keyId === 8888) {
      return HttpResponse.json(
        { code: 4003, message: 'LLM key 非本人所有', data: null },
        { status: 403 },
      )
    }
    if (keyId === 8002) {
      return HttpResponse.json(
        { code: 8002, message: 'LLM provider 无效', data: null },
        { status: 500 },
      )
    }
    if (keyId === 8003) {
      return HttpResponse.json(
        { code: 8003, message: 'LLM provider 错误', data: null },
        { status: 502 },
      )
    }
    if (keyId === 1001) {
      return HttpResponse.json(
        { code: 1001, message: '未认证', data: null },
        { status: 401 },
      )
    }

    // 正常 SSE 流:chunk1 + chunk2 + done
    const encoder = new TextEncoder()
    const stream = new ReadableStream<Uint8Array>({
      async start(controller) {
        controller.enqueue(encoder.encode(sseFrame('message', '你好')))
        controller.enqueue(encoder.encode(sseFrame('message', '世界')))
        controller.enqueue(encoder.encode(sseFrame('done', '')))
        controller.close()
      },
    })
    return new HttpResponse(stream, {
      headers: { 'Content-Type': 'text/event-stream' },
    })
  }),
]
