import { useMutation } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { apiFetch, ApiError } from '@/lib/http'
import { useAuthStore } from '@/stores/authStore'
import { toast } from 'sonner'
import type { RegisterInput } from '@/schemas/register'

/**
 * useRegister — 注册 mutation(spec §5 step 10)。
 * 成功:setAccessToken + toast + 跳 /。失败 toast.error。
 */
export function useRegister() {
  const navigate = useNavigate()
  return useMutation({
    mutationFn: (input: RegisterInput) =>
      // confirmPassword 是前端校验字段,不发给后端
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
      useAuthStore.getState().setAccessToken(data.accessToken)
      toast.success('注册成功,已自动登录')
      navigate('/')
    },
    onError: (e) => {
      // 3002 = 邀请码无效(后端 message "invalid invite code" 英文),前端中文化
      const msg =
        e instanceof ApiError && e.code === 3002
          ? '邀请码无效或已用尽'
          : e instanceof ApiError
            ? e.message
            : '注册失败,请重试'
      toast.error(msg)
    },
  })
}
