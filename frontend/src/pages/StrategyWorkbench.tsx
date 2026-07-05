import { useParams } from 'react-router-dom'
import { StageBreadcrumb } from '@/components/layout/StageBreadcrumb'

/**
 * StrategyWorkbench 页占位 — 批 1a step 13-16 实现 Monaco + AISidebar + 草稿 reload + publish。
 *
 * 含 StageBreadcrumb(canBacktest=false 占位,publish 完成后 step 16 解锁)。
 */
export function StrategyWorkbench() {
  const { id } = useParams<{ id: string }>()
  return (
    <div className="mx-auto max-w-[1240px] px-xl py-2xl text-text-primary">
      <div className="flex items-center justify-between gap-md">
        <div>
          <p className="text-label-caps text-text-muted uppercase">Workbench</p>
          <h1 className="mt-md font-display text-h1">策略 #{id}</h1>
        </div>
        <StageBreadcrumb canBacktest={false} />
      </div>
      <p className="mt-sm font-body text-body text-text-secondary">
        占位页,批 1a step 13-16 接入 Monaco 编辑器 + AISidebar + 草稿 reload + publish 流程。
      </p>
    </div>
  )
}
