import { http, HttpResponse } from 'msw'

/**
 * Auth 端点 msw handlers(测试用)。
 * envelope 格式对齐后端 ApiResponse:{code,message,data,traceId}。
 * code===0 成功(data 在 data 字段);code!==0 错误(httpFetch 的 parseBody 会抛 ApiError)。
 *
 * 测试 token 格式 `test-access-token.<base64payload>.sig`——Task 3 只验 apiFetch
 * envelope 拆包行为(返 data),不验 setAccessToken/decodeJwt,故 token 不必是合法 JWT。
 */

const OK = 0
const envelope = (data: unknown, code = OK) => ({
  code,
  message: code === OK ? 'OK' : 'error',
  data,
  traceId: 'test-trace',
})

function makeToken(userId: number, username: string, expSec: number): string {
  const payload = { userId, username, exp: Math.floor(Date.now() / 1000) + expSec }
  return `test-access-token.${btoa(JSON.stringify(payload))}.sig`
}

export const authHandlers = [
  http.post('/api/v1/auth/login', async ({ request }) => {
    const { username, password } = (await request.json()) as { username: string; password: string }
    if (password === 'wrong') return HttpResponse.json(envelope(null, 1001), { status: 401 })
    const userId = username === 'demo' ? 1 : 99
    return HttpResponse.json(envelope({ accessToken: makeToken(userId, username, 3600), expiresIn: 3600 }))
  }),
  http.post('/api/v1/auth/register', async ({ request }) => {
    const { inviteCode } = (await request.json()) as { inviteCode: string }
    if (inviteCode === 'BAD') return HttpResponse.json(envelope(null, 3002), { status: 400 })
    const userId = 200 + Math.floor(Math.random() * 100)
    return HttpResponse.json(
      envelope({ accessToken: makeToken(userId, 'newuser', 3600), expiresIn: 3600 }),
    )
  }),
  http.post('/api/v1/auth/refresh', () =>
    HttpResponse.json(envelope({ accessToken: makeToken(1, 'demo', 3600), expiresIn: 3600 })),
  ),
  http.post('/api/v1/auth/logout', () => HttpResponse.json(envelope(null))),
]
