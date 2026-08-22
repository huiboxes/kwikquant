import { useMutation } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { apiFetch } from '@/lib/http'
import { useAuthStore } from '@/stores/authStore'
import { toast } from 'sonner'
import type { RegisterInput } from '@/schemas/register'
import { clearPrivateSession } from '@/lib/clearPrivateSession'

/**
 * useRegister — 注册 mutation。
 * 成功:setAccessToken + toast + 跳 redirectTo ?? /(?from= 深链回跳,页面侧已 sanitize)。
 * 失败:不 toast——RegisterPage inline errMsg(role=alert)是唯一错误渠道(3001/3002/1003/兜底中文化齐全),
 * 避免双渠道重复 + toast 透传后端英文 message。
 */
export function useRegister(redirectTo?: string | null) {
  const navigate = useNavigate()
  return useMutation({
    mutationFn: (input: RegisterInput) =>
      // confirmPassword 是前端校验字段，不发给后端
      apiFetch<{ accessToken: string; expiresIn: number }>('/api/v1/auth/register', {
        method: 'POST',
        body: {
          username: input.username,
          email: input.email,
          password: input.password,
          inviteCode: input.inviteCode,
        },
        skipAuthRetry: true,
      }),
    onSuccess: (data) => {
      clearPrivateSession()
      useAuthStore.getState().setAccessToken(data.accessToken)
      toast.success('注册成功，已自动登录')
      navigate(redirectTo ?? '/')
    },
  })
}
