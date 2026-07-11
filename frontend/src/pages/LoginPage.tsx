import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Link } from 'react-router-dom'
import { useLogin } from '@/hooks/useLogin'
import { ApiError } from '@/lib/http'
import { loginSchema, type LoginInput } from '@/schemas/auth'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { AuthBrandBand } from './auth/AuthBrandBand'

/**
 * LoginPage — 照原型 LoginPage.jsx 移植。
 * 左品牌 band(共享 AuthBrandBand)+ 右登录表单(用户名+密码)。
 * 字段 username(后端契约 LoginRequest,非原型 email)。登录调 useLogin。
 */
export function LoginPage() {
  const login = useLogin()
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginInput>({ resolver: zodResolver(loginSchema) })

  const errMsg = login.error
    ? login.error instanceof ApiError
      ? login.error.isUnauthorized
        ? '用户名或密码错误'
        : login.error.message
      : '登录失败,请重试'
    : null

  return (
    <div className="flex min-h-screen bg-surface-canvas">
      <AuthBrandBand />
      <div className="flex min-w-[380px] flex-[0.9] items-center justify-center bg-surface-card p-xl">
        <form onSubmit={handleSubmit((input) => login.mutate(input))} className="w-full max-w-[380px]">
          <h2 className="font-display text-h1 font-medium tracking-[-0.02em] text-text-primary">继续你的策略旅程</h2>
          <p className="mt-xxs mb-lg text-body-sm text-text-muted">
            登录后从你上次停下的策略继续 — 编码、回测、模拟或实盘。
          </p>

          <label htmlFor="username" className="mb-xxs block text-label-caps text-text-muted">用户名</label>
          <Input id="username" autoComplete="username" {...register('username')} />
          {errors.username && <p className="mt-xxs text-caption text-down">{errors.username.message}</p>}

          <label htmlFor="password" className="mt-md mb-xxs block text-label-caps text-text-muted">密码</label>
          <Input id="password" type="password" autoComplete="current-password" {...register('password')} />
          {errors.password && <p className="mt-xxs text-caption text-down">{errors.password.message}</p>}

          {errMsg && <p className="mt-sm text-caption text-down" role="alert">{errMsg}</p>}

          <Button type="submit" disabled={login.isPending} className="mt-lg w-full">
            {login.isPending ? '进入中…' : '进入工作台 →'}
          </Button>

          <div className="mt-lg text-center text-body-sm text-text-muted">
            还没账户?<Link to="/register" className="text-accent hover:underline">注册</Link>
          </div>

          <div className="mt-lg rounded-md border border-dashed border-border bg-surface-card-2 p-md text-caption leading-relaxed text-text-secondary">
            <span className="font-semibold text-text-primary">演示</span> · 测试账号 demo / pass1234(msw 测试用;本地 dev 需真实账号)。
          </div>
        </form>
      </div>
    </div>
  )
}
