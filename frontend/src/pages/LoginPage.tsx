import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Link, useNavigate } from 'react-router-dom'
import { useLogin } from '@/hooks/useLogin'
import { ApiError } from '@/lib/http'
import { loginSchema, type LoginInput } from '@/schemas/auth'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { AuthBrandBand } from './auth/AuthBrandBand'

/**
 * LoginPage — 照原型 LoginPage.jsx 移植(右表单全恢复)。
 * 左品牌 band(共享)+ 右:signin/signup tab + 用户名 + 密码(忘记密码)+ 登录钮 + 分割线 + 社交按钮。
 * 字段 username(后端契约,非原型 email)。登录调 useLogin。
 */
export function LoginPage() {
  const login = useLogin()
  const navigate = useNavigate()
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
          {/* signin / signup tab(注册 → 跳 /register) */}
          <div className="mb-lg flex gap-xxs rounded-md bg-surface-card-2 p-xxs">
            <button type="button" className="flex-1 rounded-sm bg-surface-card py-xs text-body-sm font-semibold text-text-primary shadow-card">
              登录
            </button>
            <button type="button" onClick={() => navigate('/register')} className="flex-1 rounded-sm py-xs text-body-sm font-semibold text-text-muted transition-colors hover:text-text-primary">
              注册
            </button>
          </div>

          <h2 className="font-display text-h1 font-medium tracking-[-0.02em] text-text-primary">继续你的策略旅程</h2>
          <p className="mt-xxs mb-lg text-body-sm text-text-muted">
            探索适合自己的策略
          </p>

          <label htmlFor="username" className="kq-label">用户名</label>
          <Input id="username" autoComplete="username" {...register('username')} />
          {errors.username && <p className="mt-xxs text-caption text-down">{errors.username.message}</p>}

          <div className="mt-md mb-xxs flex items-center justify-between">
            <label htmlFor="password" className="kq-label mb-0">密码</label>
            {/* <button type="button" className="text-label-caps text-accent hover:underline">忘记密码?</button> */}
          </div>
          <Input id="password" type="password" autoComplete="current-password" {...register('password')} />
          {errors.password && <p className="mt-xxs text-caption text-down">{errors.password.message}</p>}

          {errMsg && <p className="mt-sm text-caption text-down" role="alert">{errMsg}</p>}

          <Button type="submit" disabled={login.isPending} className="mt-lg w-full">
            {login.isPending ? '进入中…' : '进入工作台 →'}
          </Button>

          {/* 分割线 */}
          {/* <div className="my-lg flex items-center gap-sm">
            <div className="h-px flex-1 bg-border" />
            <span className="text-label-caps text-text-muted">或继续使用</span>
            <div className="h-px flex-1 bg-border" />
          </div>*/}

          {/* 社交按钮(视觉占位,OAuth 后续接) */}
          {/* <div className="grid grid-cols-3 gap-xs">
            {['Google', 'GitHub', 'Solana'].map((p) => (
              <Button key={p} type="button" variant="ghost" size="sm">{p}</Button>
            ))}
          </div>*/}

          <div className="mt-md text-center text-label-caps text-text-muted">
            还没账户?<Link to="/register" className="text-accent hover:underline">注册</Link>
          </div>
        </form>
      </div>
    </div>
  )
}
