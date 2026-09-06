import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { useRegister } from '@/hooks/useRegister'
import { ApiError } from '@/lib/http'
import { sanitizeRedirectTarget, authUrlWithFrom } from '@/lib/redirect'
import { docUrl } from '@/lib/docs'
import { registerSchema, type RegisterInput } from '@/schemas/register'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { AuthBrandBand } from './auth/AuthBrandBand'
import { BrandMark } from '@/components/BrandMark'

/**
 * RegisterPage — 注册页。
 * 左品牌 band(共享)+ 右:signin/signup tab(signup active)+ 用户名/邮箱/密码/确认密码/邀请码 + 注册钮 + 社交。
 * confirmPassword 是前端校验字段，useRegister 不发后端。
 */
export function RegisterPage() {
  // ?from= 深链回跳(登录页切注册时透传):注册成功即回原页面;切回登录页同样透传
  const [searchParams] = useSearchParams()
  const from = sanitizeRedirectTarget(searchParams.get('from'))
  const registerMutation = useRegister(from)
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
        : registerMutation.error.code === 3001
          ? '用户名或邮箱已被使用，请更换后重试'
          : registerMutation.error.code === 1003
            ? '注册尝试过于频繁，请稍后再试'
            : '注册服务暂时不可用，请稍后再试'
      : '注册失败，请重试'
    : null

  return (
    <div className="flex min-h-screen max-w-full overflow-x-hidden bg-surface-canvas">
      <AuthBrandBand />
      <div className="flex min-w-0 flex-1 flex-col items-center justify-center bg-surface-card p-base sm:p-xl lg:flex-[0.9]">
        {/* 窄屏 band 隐藏，品牌在表单侧兜底露出 */}
        <div className="mb-xl flex items-center gap-xs lg:hidden">
          <BrandMark className="h-[28px] w-[28px]" />
          <span className="text-body font-bold text-text-primary">KwikQuant</span>
        </div>
        <form onSubmit={handleSubmit((input) => registerMutation.mutate(input))} className="w-full max-w-[380px]">
          {/* signin / signup tab(signin → 跳 /login) */}
          <div className="mb-lg flex gap-xxs rounded-md bg-surface-card-2 p-xxs">
            <button type="button" onClick={() => navigate(authUrlWithFrom('/login', from))} className="flex-1 rounded-sm py-xs text-body-sm font-semibold text-text-muted transition-colors hover:text-text-primary">
              登录
            </button>
            <button type="button" className="flex-1 rounded-sm bg-surface-card py-xs text-body-sm font-semibold text-text-primary shadow-card">
              注册
            </button>
          </div>

          <h2 className="font-semibold text-h1 text-text-primary">创建账户</h2>
          {/* 邀请制是获客链路末端的真实门槛:做成醒目说明卡,无码用户一眼看到出路 */}
          <div className="mt-xxs mb-lg rounded-md bg-surface-card-2 px-sm py-xs text-body-sm leading-[1.5] text-text-secondary">
            KwikQuant 当前采用邀请制，请输入邀请码完成注册。还没有邀请码？联系管理员或从社区渠道获取。
          </div>

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
          {/* 邀请制门槛的出路：告诉用户码从哪来，避免无码新用户面对死胡同流失 */}
          <p className="mt-xxs text-caption text-text-muted">
            邀请码由实例管理员发放；自托管部署可用 SQL 生成，见{' '}
            <a
              href={docUrl('docs/quickstart.md')}
              target="_blank"
              rel="noopener noreferrer"
              className="text-accent hover:underline"
            >
              快速上手
            </a>
            。
          </p>

          {errMsg && <p className="mt-sm text-caption text-down" role="alert">{errMsg}</p>}

          <Button type="submit" disabled={registerMutation.isPending} className="mt-lg w-full">
            {registerMutation.isPending ? '创建中…' : '创建账户 →'}
          </Button>

          {/* <div className="my-lg flex items-center gap-sm">
            <div className="h-px flex-1 bg-border" />
            <span className="text-label-caps text-text-muted">或继续使用</span>
            <div className="h-px flex-1 bg-border" />
          </div>*/}

          {/* <div className="grid grid-cols-3 gap-xs">
            {['Google', 'GitHub', 'Solana'].map((p) => (
              <Button key={p} type="button" variant="ghost" size="sm">{p}</Button>
            ))}
          </div>*/}

          <div className="mt-md text-center text-label-caps text-text-muted">
            已有账户？<Link to={authUrlWithFrom('/login', from)} className="text-accent hover:underline">登录</Link>
          </div>
        </form>
        <p className="mt-xxl max-w-[380px] text-center text-caption text-text-muted">
          模拟盘免费验证 · 实盘交易涉及风险，请审慎决策。
        </p>
      </div>
    </div>
  )
}
