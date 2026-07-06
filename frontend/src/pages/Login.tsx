import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Link } from 'react-router-dom'
import { loginSchema, type LoginInput } from '@/schemas/auth'
import { useLogin } from '@/hooks/useLogin'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'

/**
 * Login 页(spec §5 step 10)。
 *
 * react-hook-form + zod + useLogin mutation。成功跳 /。
 * 登录页交互(内存召回):hover cursor-pointer + 按钮点击反馈 + 输入框有内容视觉。
 */
export function Login() {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginInput>({
    resolver: zodResolver(loginSchema),
    defaultValues: { username: '', password: '' },
  })
  const login = useLogin()

  return (
    <div className="flex min-h-screen items-center justify-center bg-surface-canvas px-xl text-text-primary">
      <form
        onSubmit={handleSubmit((data) => login.mutate(data))}
        className="w-full max-w-sm rounded-xl bg-surface-card p-lg shadow-card"
        noValidate
      >
        <p className="text-label-caps uppercase tracking-[0.35em] text-text-muted">Auth</p>
        <h1 className="mt-md font-display text-h2">登录</h1>

        <div className="mt-xl space-y-md">
          <div className="space-y-sm">
            <label htmlFor="username" className="font-body text-body-sm text-text-secondary">
              用户名
            </label>
            <Input
              id="username"
              autoComplete="username"
              placeholder="请输入用户名"
              {...register('username')}
            />
            {errors.username && (
              <p className="font-body text-caption text-down">{errors.username.message}</p>
            )}
          </div>

          <div className="space-y-sm">
            <label htmlFor="password" className="font-body text-body-sm text-text-secondary">
              密码
            </label>
            <Input
              id="password"
              type="password"
              autoComplete="current-password"
              placeholder="请输入密码"
              {...register('password')}
            />
            {errors.password && (
              <p className="font-body text-caption text-down">{errors.password.message}</p>
            )}
          </div>
        </div>

        <Button
          type="submit"
          className="mt-xl w-full cursor-pointer"
          disabled={login.isPending}
        >
          {login.isPending ? '登录中…' : '登录'}
        </Button>

        <p className="mt-md text-center font-body text-body-sm text-text-secondary">
          还没有账号?{' '}
          <Link to="/register" className="text-accent hover:text-accent-soft">
            注册
          </Link>
        </p>
      </form>
    </div>
  )
}
