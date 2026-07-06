import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Link } from 'react-router-dom'
import { registerSchema, type RegisterInput } from '@/schemas/register'
import { useRegister } from '@/hooks/useRegister'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'

/**
 * Register 页(spec §5 step 10)。
 * react-hook-form + zod(含 confirmPassword 一致性校验) + useRegister mutation。成功跳 /。
 * 邀请码字段:注册门禁,后端校验(无效码 400 + 3002)。
 */
export function Register() {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<RegisterInput>({
    resolver: zodResolver(registerSchema),
    defaultValues: { username: '', email: '', password: '', confirmPassword: '', inviteCode: '' },
  })
  const registerMut = useRegister()

  return (
    <div className="flex min-h-screen items-center justify-center bg-surface-canvas px-xl text-text-primary">
      <form
        onSubmit={handleSubmit((data) => registerMut.mutate(data))}
        className="w-full max-w-sm rounded-xl bg-surface-card p-2xl shadow-card"
        noValidate
      >
        <p className="text-label-caps uppercase tracking-[0.35em] text-text-muted">Auth</p>
        <h1 className="mt-md font-display text-h2">注册</h1>

        <div className="mt-xl space-y-md">
          <div className="space-y-sm">
            <label htmlFor="username" className="font-body text-body-sm text-text-secondary">
              用户名
            </label>
            <Input
              id="username"
              autoComplete="username"
              placeholder="3-64 字符"
              {...register('username')}
            />
            {errors.username && (
              <p className="font-body text-caption text-down">{errors.username.message}</p>
            )}
          </div>

          <div className="space-y-sm">
            <label htmlFor="email" className="font-body text-body-sm text-text-secondary">
              邮箱
            </label>
            <Input
              id="email"
              type="email"
              autoComplete="email"
              placeholder="you@example.com"
              {...register('email')}
            />
            {errors.email && (
              <p className="font-body text-caption text-down">{errors.email.message}</p>
            )}
          </div>

          <div className="space-y-sm">
            <label htmlFor="password" className="font-body text-body-sm text-text-secondary">
              密码
            </label>
            <Input
              id="password"
              type="password"
              autoComplete="new-password"
              placeholder="8-128 字符"
              {...register('password')}
            />
            {errors.password && (
              <p className="font-body text-caption text-down">{errors.password.message}</p>
            )}
          </div>

          <div className="space-y-sm">
            <label htmlFor="confirmPassword" className="font-body text-body-sm text-text-secondary">
              确认密码
            </label>
            <Input
              id="confirmPassword"
              type="password"
              autoComplete="new-password"
              placeholder="再次输入密码"
              {...register('confirmPassword')}
            />
            {errors.confirmPassword && (
              <p className="font-body text-caption text-down">
                {errors.confirmPassword.message}
              </p>
            )}
          </div>

          <div className="space-y-sm">
            <label htmlFor="inviteCode" className="font-body text-body-sm text-text-secondary">
              邀请码
            </label>
            <Input
              id="inviteCode"
              autoComplete="off"
              placeholder="如 KWIK-DEV-001"
              {...register('inviteCode')}
            />
            {errors.inviteCode && (
              <p className="font-body text-caption text-down">{errors.inviteCode.message}</p>
            )}
          </div>
        </div>

        <Button
          type="submit"
          className="mt-xl w-full cursor-pointer"
          disabled={registerMut.isPending}
        >
          {registerMut.isPending ? '注册中…' : '注册'}
        </Button>

        <p className="mt-md text-center font-body text-body-sm text-text-secondary">
          已有账号?{' '}
          <Link to="/login" className="text-accent hover:text-accent-soft">
            登录
          </Link>
        </p>
      </form>
    </div>
  )
}
