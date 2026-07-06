import { useMutation } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { apiFetch, ApiError } from '@/lib/http'
import { useAuthStore } from '@/stores/authStore'
import { toast } from 'sonner'
import type { LoginInput } from '@/schemas/auth'

/**
 * useLogin — 登录 mutation(spec §5 step 10)。
 *
 * 成功:setAccessToken + toast + 跳 /。
 * 失败:toast.error(后端 message,如"用户名或密码错误")。
 * 401 不重放(skipAuthRetry):login 端点 401 = 凭证错误,不是 token 过期。
 */
export function useLogin() {
  const navigate = useNavigate()
  return useMutation({
    mutationFn: (input: LoginInput) =>
      apiFetch<{ accessToken: string; expiresIn: number }>('/api/v1/auth/login', {
        method: 'POST',
        body: input,
        skipAuthRetry: true,
      }),
    onSuccess: (data) => {
      useAuthStore.getState().setAccessToken(data.accessToken)
      toast.success('登录成功')
      navigate('/')
    },
    onError: (e) => {
      // 1001 = 凭证错误(后端 message "invalid credentials" 是英文),前端中文化提示;
      // 其他 ApiError 透传后端 message,非 ApiError 兜底通用文案。
      const msg =
        e instanceof ApiError && e.isUnauthorized
          ? '用户名或密码错误'
          : e instanceof ApiError
            ? e.message
            : '登录失败,请重试'
      toast.error(msg)
    },
  })
}
