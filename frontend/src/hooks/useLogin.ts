import { useMutation } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { apiFetch } from '@/lib/http'
import { useAuthStore } from '@/stores/authStore'
import { toast } from 'sonner'
import type { LoginInput } from '@/schemas/auth'
import { clearPrivateSession } from '@/lib/clearPrivateSession'

/**
 * useLogin — 登录 mutation。
 *
 * 成功:setAccessToken + toast + 跳 redirectTo ?? /(redirectTo = /login?from= 深链回跳目标,
 * 页面侧已过 sanitizeRedirectTarget,此处不再重复校验)。
 * 失败:不 toast——LoginPage inline errMsg(role=alert)是唯一错误渠道,中文化映射齐全,
 * 避免双渠道重复提示 + toast 透传后端英文 message。
 * 401 不重放(skipAuthRetry):login 端点 401 = 凭证错误，不是 token 过期。
 */
export function useLogin(redirectTo?: string | null) {
  const navigate = useNavigate()
  return useMutation({
    mutationFn: (input: LoginInput) =>
      apiFetch<{ accessToken: string; expiresIn: number }>('/api/v1/auth/login', {
        method: 'POST',
        body: input,
        skipAuthRetry: true,
      }),
    onSuccess: (data) => {
      clearPrivateSession()
      useAuthStore.getState().setAccessToken(data.accessToken)
      toast.success('登录成功')
      navigate(redirectTo ?? '/')
    },
  })
}
