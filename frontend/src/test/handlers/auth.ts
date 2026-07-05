import { HttpResponse, http } from 'msw'

/**
 * auth 端点 MSW handler(spec §5 step 6,批 1a 测试用)。
 *
 * 后端 ApiResponse envelope:{code:0, message, data}。http.ts parseBody 双解包。
 * mock token:JWT 3 段,sub=42,username=alice,exp=未来(测试不验签,只 decode)。
 */

const FUTURE_EXP = Math.floor(Date.now() / 1000) + 3600

// 简单 mock JWT(不验签,前端只 decode payload)
const mockToken = (userId = 42, username = 'alice') => {
  const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).replace(/=/g, '')
  const payload = btoa(
    JSON.stringify({ sub: String(userId), username, exp: FUTURE_EXP }),
  ).replace(/=/g, '')
  return `${header}.${payload}.mock-signature`
}

export const authHandlers = [
  http.post('/api/v1/auth/login', async ({ request }) => {
    const body = (await request.json()) as { username?: string; password?: string }
    if (body.username === 'alice' && body.password === 'password123') {
      return HttpResponse.json({
        code: 0,
        message: 'ok',
        data: { accessToken: mockToken(42, 'alice'), expiresIn: 3600 },
      })
    }
    return HttpResponse.json(
      { code: 1001, message: '用户名或密码错误', data: null },
      { status: 401 },
    )
  }),

  http.post('/api/v1/auth/register', async ({ request }) => {
    const body = (await request.json()) as { username?: string }
    return HttpResponse.json({
      code: 0,
      message: 'ok',
      data: { accessToken: mockToken(43, body.username ?? 'newuser'), expiresIn: 3600 },
    })
  }),

  http.post('/api/v1/auth/refresh', () => {
    // refresh cookie 存在则换新 access token;测试默认成功
    return HttpResponse.json({
      code: 0,
      message: 'ok',
      data: { accessToken: mockToken(42, 'alice'), expiresIn: 3600 },
    })
  }),

  http.post('/api/v1/auth/logout', () => {
    return HttpResponse.json({ code: 0, message: 'ok', data: null })
  }),
]
