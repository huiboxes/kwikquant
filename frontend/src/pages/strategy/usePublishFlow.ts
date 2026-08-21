import { toast } from 'sonner'
import type { MutableRefObject } from 'react'
import type {
  usePublishCode,
  useReadyStrategy,
  useCreateCodeDraft,
  useUpdateCodeDraft,
} from '@/hooks/useStrategies'
import type { StrategyDetailDto } from '@/api/strategy'
import type { BacktestRange } from './BottomControlBar'

/**
 * usePublishFlow — 发布版本编排(从 StrategyPage 拆出，Wave 3.2a)。
 *
 * 职责：发布前 snapshot 草稿(updateDraft)→ publish → (DRAFT 策略)ready → 自动开新草稿继承
 * 刚发布代码；若发布由"先发布后回测"预检触发(pendingBacktestRange)，发布成功后自动提交回测。
 *
 * mutation(publish/ready/createDraft/updateDraft)均由页面传入单实例，loading 态与页面共享。
 */
export function usePublishFlow(opts: {
  selected: StrategyDetailDto | null
  draftCodeId: number | null
  draftChangelog: string
  /** 编辑器当前内容(useStrategyAutoSave 的 codeRef)。 */
  codeRef: MutableRefObject<string>
  /** codeDetail.sourceCode(codeRef 空时兜底)。 */
  codeDetailSource: string | undefined
  /** codeRef 与 codeDetail 均空时的模板兜底。 */
  template: string
  setActiveCodeIdOverride: (id: number | null) => void
  resetAutoSave: () => void
  /** 发布前取消 pending debounce 保存(防与发布 updateDraft race)。 */
  cancelPendingSave: () => void
  setShowPublish: (open: boolean) => void
  /** 发布成功后取走待回测 range(useBacktestExecution 的预检 pending)。 */
  consumePendingBacktestRange: () => BacktestRange | null
  /** 自动回测(skipPublishCheck，代码刚 PUBLISHED)。 */
  handleSubmitBacktest: (range: BacktestRange, opts?: { skipPublishCheck?: boolean }) => void
  publishMut: ReturnType<typeof usePublishCode>
  readyMut: ReturnType<typeof useReadyStrategy>
  createDraftMut: ReturnType<typeof useCreateCodeDraft>
  updateDraftMut: ReturnType<typeof useUpdateCodeDraft>
}) {
  const {
    selected,
    draftCodeId,
    draftChangelog,
    codeRef,
    codeDetailSource,
    template,
    setActiveCodeIdOverride,
    resetAutoSave,
    cancelPendingSave,
    setShowPublish,
    consumePendingBacktestRange,
    handleSubmitBacktest,
    publishMut,
    readyMut,
    createDraftMut,
    updateDraftMut,
  } = opts

  function handlePublish(changelog: string) {
    if (!selected || draftCodeId == null) {
      toast.warning('没有可发布的草稿代码')
      return
    }
    const strategyId = selected.id
    const codeId = draftCodeId
    // 发布前 snapshot 刚发布代码(新草稿继承，不依赖 publish 后 codeDetail race)
    const publishedSourceCode = codeRef.current || codeDetailSource || template
    cancelPendingSave() // 防 pending debounce 保存与发布 updateDraft race
    updateDraftMut.mutate(
      {
        strategyId,
        codeId,
        req: {
          sourceCode: codeRef.current || codeDetailSource || template,
          changelog: changelog || draftChangelog || '',
        },
      },
      {
        onSuccess: () => {
          publishMut.mutate(
            { strategyId, codeId },
            {
              onSuccess: () => {
                // 问题 1 自动回测：用户从回测按钮触发发布(pendingBacktestRange 非空)
                // → 发布成功后自动回测(skipPublishCheck 跳过预检，代码刚 PUBLISHED)。
                const pendingRange = consumePendingBacktestRange()
                if (pendingRange) {
                  handleSubmitBacktest(pendingRange, { skipPublishCheck: true })
                }
                // 策略 DRAFT(首次发布)才 ready→READY；已 READY/RUNNING(新版本发布)不需 ready,
                // 否则已就绪策略 ready 失败(状态不可转)误报"标记就绪失败"
                const wasDraft = selected?.status === 'DRAFT'
                const finish = () => {
                  toast.success('版本已发布', {
                    description: wasDraft ? '策略已就绪可启动' : '新版本已上线',
                  })
                  setShowPublish(false)
                  resetAutoSave()
                  // 自动开新草稿，继承刚发布代码(用户继续迭代，不用手动 +)
                  // 后端 createDraft 409 校验:publish 后无 DRAFT，不冲突
                  createDraftMut.mutate(
                    {
                      strategyId,
                      req: { sourceCode: publishedSourceCode, changelog: '基于上一版本迭代' },
                    },
                    {
                      onSuccess: (newDraft) => setActiveCodeIdOverride(newDraft.id),
                      onError: () => toast.warning('新草稿创建失败，可手动新建'),
                    },
                  )
                }
                if (wasDraft) {
                  readyMut.mutate(strategyId, {
                    onSuccess: finish,
                    onError: () =>
                      toast.warning('代码已发布，标记就绪失败，可手动启动'),
                  })
                } else {
                  finish()
                }
              },
              onError: () => toast.error('发布失败，请重试'),
            },
          )
        },
        onError: () => toast.error('更新草稿失败，请重试'),
      },
    )
  }

  return { handlePublish, publishing: publishMut.isPending || readyMut.isPending }
}
