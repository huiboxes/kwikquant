import { Copy, Eye } from 'lucide-react'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Chip } from '@/components/Chip'
import type { TemplateDto } from '@/api/template'

interface TemplateCardProps {
  template: TemplateDto
  /** fork 中(该卡 pending，按钮转"fork 中…") */
  forking: boolean
  /** 全局 fork 锁(任一 fork 在途即禁用所有卡按钮，防跨卡并发重复 fork) */
  forkDisabled?: boolean
  onFork: (key: string) => void
  /** 打开详情 dialog(代码预览) */
  onDetail: (key: string) => void
}

/**
 * 模板卡片(3→2→1 响应式网格)。无边界白卡，hover 走底色反馈不浮起。
 * fork 走中性暗底：一屏十几张卡都铺品牌橙会把主色稀释掉，
 * 橙色留给详情弹窗里的确认动作和页面级 CTA。
 */
export function TemplateCard({ template, forking, forkDisabled = false, onFork, onDetail }: TemplateCardProps) {
  const t = template

  return (
    <Card className="flex flex-col gap-3.5 p-6 transition-colors motion-fast hover:bg-surface-hover">
      <div className="flex items-start justify-between gap-2">
        <h3 className="text-h3 text-text-primary">{t.name}</h3>
        <div className="flex flex-wrap justify-end gap-1">
          {t.tags.map((tag) => (
            <Chip key={tag} label={tag} color="neutral" />
          ))}
        </div>
      </div>

      <p className="min-h-[60px] text-caption leading-[1.6] text-text-secondary">{t.description}</p>

      {/* 推荐配置：数字/代码走 mono(DESIGN.md 数字 mono+tnum) */}
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-lg bg-surface-card-2 px-2.5 py-2 text-caption text-text-muted">
        <span className="font-mono">{t.symbol}</span>
        <span aria-hidden>·</span>
        <span className="font-mono">{t.intervalValue}</span>
        <span aria-hidden>·</span>
        <span className="font-mono">{t.exchange}</span>
        <span aria-hidden>·</span>
        <span>
          推荐回测 <span className="font-mono">{t.backtestWindowDays}</span> 天
        </span>
      </div>

      <div className="mt-auto flex gap-2">
        <Button variant="outline" size="sm" className="flex-1" onClick={() => onDetail(t.key)}>
          <Eye className="size-3.5" aria-hidden />
          查看详情
        </Button>
        <Button size="sm" className="flex-1 bg-onyx hover:bg-onyx/85" disabled={forking || forkDisabled} onClick={() => onFork(t.key)}>
          <Copy className="size-3.5" aria-hidden />
          {forking ? '复制中…' : '使用模板'}
        </Button>
      </div>
    </Card>
  )
}
