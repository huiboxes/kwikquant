import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Link, useNavigate } from 'react-router-dom'
import { useRegister } from '@/hooks/useRegister'
import { ApiError } from '@/lib/http'
import { registerSchema, type RegisterInput } from '@/schemas/register'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { AuthBrandBand } from './auth/AuthBrandBand'

/**
 * RegisterPage — 照原型( LoginPage signup 模式)移植。
 * 左品牌 band(共享)+ 右:signin/signup tab(signup active)+ 用户名/邮箱/密码/确认密码/邀请码 + 注册钮 + 社交。
 * confirmPassword 是前端校验字段,useRegister 不发后端。
 */
export function RegisterPage() {
  const registerMutation = useRegister()
  const navigate = useNavigate()
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
          {/* signin / signup tab(signin → 跳 /login) */}
          <div className="mb-lg flex gap-xxs rounded-md bg-surface-card-2 p-xxs">
            <button type="button" onClick={() => navigate('/login')} className="flex-1 rounded-sm py-xs text-body-sm font-semibold text-text-muted transition-colors hover:text-text-primary">
              登录
            </button>
            <button type="button" className="flex-1 rounded-sm bg-surface-card py-xs text-body-sm font-semibold text-text-primary shadow-card">
              注册
            </button>
          </div>

          <h2 className="font-display text-h1 font-medium tracking-[-0.02em] text-text-primary">创建账户</h2>
          <p className="mt-xxs mb-lg text-body-sm text-text-muted">
            KwikQuant 暂为邀请制,请输入邀请码完成注册。
          </p>

          <label htmlFor="reg-username" className="kq-label">用户名</label>
          <Input id="reg-username" autoComplete="username" {...register('username')} />
          {errors.username && <p className="mt-xxs text-caption text-down">{errors.username.message}</p>}

          <label htmlFor="reg-email" className="kq-label mt-md">邮箱</label>
          <Input id="reg-email" type="email" autoComplete="email" {...register('email')} />
          {errors.email && <p className="mt-xxs text-caption text-down">{errors.email.message}</p>}

          <label htmlFor="reg-password" className="kq-label mt-md">密码</label>
          <Input id="reg-password" type="password" autoComplete="new-password" {...register('password')} />
          {errors.password && <p className="mt-xxs text-caption text-down">{errors.password.message}</p>}

          <label htmlFor="reg-confirm" className="kq-label mt-md">确认密码</label>
          <Input id="reg-confirm" type="password" autoComplete="new-password" {...register('confirmPassword')} />
          {errors.confirmPassword && <p className="mt-xxs text-caption text-down">{errors.confirmPassword.message}</p>}

          <label htmlFor="reg-invite" className="kq-label mt-md">邀请码</label>
          <Input id="reg-invite" placeholder="KQ-INV-XXXX-XXXX" {...register('inviteCode')} />
          {errors.inviteCode && <p className="mt-xxs text-caption text-down">{errors.inviteCode.message}</p>}

          {errMsg && <p className="mt-sm text-caption text-down" role="alert">{errMsg}</p>}

          <Button type="submit" disabled={registerMutation.isPending} className="mt-lg w-full">
            {registerMutation.isPending ? '创建中…' : '创建账户 →'}
          </Button>

          <div className="my-lg flex items-center gap-sm">
            <div className="h-px flex-1 bg-border" />
            <span className="text-label-caps text-text-muted">或继续使用</span>
            <div className="h-px flex-1 bg-border" />
          </div>

          <div className="grid grid-cols-3 gap-xs">
            {['Google', 'GitHub', 'Solana'].map((p) => (
              <Button key={p} type="button" variant="ghost" size="sm">{p}</Button>
            ))}
          </div>

          <div className="mt-md text-center text-label-caps text-text-muted">
            已有账户?<Link to="/login" className="text-accent hover:underline">登录</Link>
          </div>
        </form>
      </div>
    </div>
  )
}
