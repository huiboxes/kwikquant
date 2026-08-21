import Editor from '@monaco-editor/react'
import '@/lib/monaco'
import { Copy } from 'lucide-react'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Chip } from '@/components/Chip'
import { LoadingState } from '@/components/feedback/LoadingState'
import { ErrorState } from '@/components/ErrorState'
import { useTemplateDetail } from '@/hooks/useTemplates'

interface TemplateDetailDialogProps {
  /** 打开的模板 key;null = 关闭 */
  templateKey: string | null
  onOpenChange: (open: boolean) => void
  /** fork 中(按钮转"fork 中…") */
  forking: boolean
  /** 全局 fork 锁(任一 fork 在途即禁用，防并发重复 fork) */
  forkDisabled?: boolean
  onFork: (key: string) => void
}

/**
 * 模板详情 dialog:元数据 + 只读源码预览(Monaco vs-dark，与策略工作台一致)+ fork。
 * key 重挂载范式：父层按 templateKey 作 key，打开时重新拉详情。
 */
export function TemplateDetailDialog({
  templateKey,
  onOpenChange,
  forking,
  forkDisabled = false,
  onFork,
}: TemplateDetailDialogProps) {
  const { data: detail, isLoading, error, refetch } = useTemplateDetail(templateKey)

  return (
    <Dialog open={templateKey != null} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[720px]">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            {detail?.name ?? '模板详情'}
            {detail?.tags.map((tag) => (
              <Chip key={tag} label={tag} color="neutral" />
            ))}
          </DialogTitle>
          {detail && (
            <DialogDescription>
              {detail.symbol} · {detail.intervalValue} · {detail.exchange} · 推荐回测{' '}
              {detail.backtestWindowDays} 天
            </DialogDescription>
          )}
        </DialogHeader>

        {error ? (
          <ErrorState message={(error as Error).message} onRetry={() => refetch()} />
        ) : isLoading || !detail ? (
          <LoadingState rows={6} />
        ) : (
          <div className="flex flex-col gap-3">
            <p className="text-caption leading-[1.6] text-text-secondary">{detail.description}</p>
            {/* 源码预览：只读 Monaco(python,vs-dark),fork 后可编辑 */}
            <div className="overflow-hidden rounded-lg border border-border-soft">
              <Editor
                height="320px"
                defaultLanguage="python"
                theme="vs-dark"
                value={detail.sourceCode}
                options={{
                  readOnly: true,
                  minimap: { enabled: false },
                  fontSize: 13,
                  lineNumbers: 'on',
                  scrollBeyondLastLine: false,
                  tabSize: 4,
                  automaticLayout: true,
                }}
              />
            </div>
            <p className="text-caption-sm text-text-muted">
              fork 后源码会复制为你的策略草稿并发布，可在策略工作台自由修改；首次回测按推荐窗口自动提交。
            </p>
          </div>
        )}

        <DialogFooter>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            关闭
          </Button>
          <Button disabled={!detail || forking || forkDisabled} onClick={() => detail && onFork(detail.key)}>
            <Copy className="size-3.5" aria-hidden />
            {forking ? 'fork 中…' : 'fork 使用'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
