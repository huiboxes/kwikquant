import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Link } from 'react-router-dom'
import { useRegister } from '@/hooks/useRegister'
import { ApiError } from '@/lib/http'
import { registerSchema, type RegisterInput } from '@/schemas/register'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { AuthBrandBand } from './auth/AuthBrandBand'

/**
 * RegisterPage — 照原型( LoginPage 的 signup 模式)移植。
 * 左品牌 band(共享)+ 右注册表单(用户名/邮箱/密码/确认密码/邀请码)。
 * confirmPassword 是前端校验字段,useRegister 不发后端。注册调 useRegister。
 */
export function RegisterPage() {
  const registerMutation = useRegister()
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<RegisterInput>({ resolver: zodResolver(registerSchema) })

  const errMsg = registerMutation.error
    ? registerMutation.error instanceof ApiError
      ? registerMutation.error.code === 3002
        ? '邀请码无效或已用尽'
        : registerMutation.error.message
      : '注册失败,请重试'
    : null

  return (
    <div className="flex min-h-screen bg-surface-canvas">
      <AuthBrandBand />
      <div className="flex min-w-[380px] flex-[0.9] items-center justify-center bg-surface-card p-xl">
        <form onSubmit={handleSubmit((input) => registerMutation.mutate(input))} className="w-full max-w-[380px]">
          <h2 className="font-display text-h1 font-medium tracking-[-0.02em] text-text-primary">创建账户</h2>
          <p className="mt-xxs mb-lg text-body-sm text-text-muted">
            KwikQuant 暂为邀请制,请输入邀请码完成注册。
          </p>

          <label htmlFor="reg-username" className="mb-xxs block text-label-caps text-text-muted">用户名</label>
          <Input id="reg-username" autoComplete="username" {...register('username')} />
          {errors.username && <p className="mt-xxs text-caption text-down">{errors.username.message}</p>}

          <label htmlFor="reg-email" className="mt-md mb-xxs block text-label-caps text-text-muted">邮箱</label>
          <Input id="reg-email" type="email" autoComplete="email" {...register('email')} />
          {errors.email && <p className="mt-xxs text-caption text-down">{errors.email.message}</p>}

          <label htmlFor="reg-password" className="mt-md mb-xxs block text-label-caps text-text-muted">密码</label>
          <Input id="reg-password" type="password" autoComplete="new-password" {...register('password')} />
          {errors.password && <p className="mt-xxs text-caption text-down">{errors.password.message}</p>}

          <label htmlFor="reg-confirm" className="mt-md mb-xxs block text-label-caps text-text-muted">确认密码</label>
          <Input id="reg-confirm" type="password" autoComplete="new-password" {...register('confirmPassword')} />
          {errors.confirmPassword && <p className="mt-xxs text-caption text-down">{errors.confirmPassword.message}</p>}

          <label htmlFor="reg-invite" className="mt-md mb-xxs block text-label-caps text-text-muted">邀请码</label>
          <Input id="reg-invite" placeholder="KQ-INV-XXXX-XXXX" {...register('inviteCode')} />
          {errors.inviteCode && <p className="mt-xxs text-caption text-down">{errors.inviteCode.message}</p>}

          {errMsg && <p className="mt-sm text-caption text-down" role="alert">{errMsg}</p>}

          <Button type="submit" disabled={registerMutation.isPending} className="mt-lg w-full">
            {registerMutation.isPending ? '创建中…' : '创建账户 →'}
          </Button>

          <div className="mt-lg text-center text-body-sm text-text-muted">
            已有账户?<Link to="/login" className="text-accent hover:underline">登录</Link>
          </div>
        </form>
      </div>
    </div>
  )
}
