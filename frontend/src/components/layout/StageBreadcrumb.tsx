import { Fragment } from 'react'
import { useSearchParams } from 'react-router-dom'
import { ChevronRight } from 'lucide-react'
import { cn } from '@/lib/cn'
import { deriveStage, STAGES, type Stage } from './stage'

const STAGE_LABEL: Record<Stage, string> = {
  code: '编码',
  backtest: '回测',
}

/**
 * StageBreadcrumb — 策略工作台阶段面包屑(spec §5 #6 + §3.5/§4.1)。
 *
 * URL ?stage= 深链:点击 stage 切换 setSearchParams({stage: next}),保留其他 params(如 taskId)。
 * backtest 态需 canBacktest=true(publish 完成解锁,批 1a 默认 false)才可跳。
 *
 * DESIGN.md token: 激活态 bg-primary text-accent;非激活 text-text-secondary + hover;
 * disabled text-muted + cursor-not-allowed。
 */
export function StageBreadcrumb({ canBacktest }: { canBacktest: boolean }) {
  const [params, setParams] = useSearchParams()
  const current = deriveStage(params)

  const go = (next: Stage) => {
    if (next === 'backtest' && !canBacktest) return
    setParams((prev) => {
      const p = new URLSearchParams(prev)
      p.set('stage', next)
      return p
    })
  }

  return (
    <nav className="flex items-center gap-sm" aria-label="策略阶段导航">
      {STAGES.map((stage, i) => {
        const isCurrent = current === stage
        const isDisabled = stage === 'backtest' && !canBacktest
        return (
          <Fragment key={stage}>
            {i > 0 && <ChevronRight className="h-[16px] w-[16px] text-text-muted" aria-hidden />}
            <button
              type="button"
              onClick={() => go(stage)}
              disabled={isDisabled}
              aria-current={isCurrent ? 'step' : undefined}
              className={cn(
                'rounded-md px-md py-1 font-body text-body-sm transition-colors',
                isCurrent && 'bg-primary text-accent',
                !isCurrent && isDisabled && 'cursor-not-allowed text-text-muted/60',
                !isCurrent && !isDisabled && 'text-text-secondary hover:bg-surface-hover hover:text-text-primary',
              )}
            >
              {STAGE_LABEL[stage]}
            </button>
          </Fragment>
        )
      })}
    </nav>
  )
}
