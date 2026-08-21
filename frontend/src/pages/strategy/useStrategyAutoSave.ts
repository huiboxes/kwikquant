import { useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import type { useUpdateCodeDraft } from '@/hooks/useStrategies'

/**
 * useStrategyAutoSave — 策略工作台自动保存(从 StrategyPage 拆出，Wave 3.2a)。
 *
 * 职责：代码编辑 → dirty + 3s debounce 倒计时显示 → updateCodeDraft 保存；Cmd+S 立即保存
 * (跳过 debounce)；切策略/发布/删草稿时 resetAutoSave 清 pending timer(防旧 timer 用
 * 新代码污染旧策略草稿，B-1)。
 *
 * codeRef 暴露给发布/另存流程(快照当前编辑器内容，不依赖 codeDetail refetch race)。
 *
 * updateDraftMut 由页面传入(与发布流程共享单实例，loading 态一致)。
 */
export function useStrategyAutoSave(opts: {
  /** 当前选中策略 id(null 时不排保存)。 */
  strategyId: number | null
  /** 当前草稿 code id(null 时不排保存)。 */
  draftCodeId: number | null
  /** 草稿 changelog(保存时透传)。 */
  draftChangelog: string
  /** 更新草稿 mutation(页面级单实例，与 usePublishFlow 共享)。 */
  updateDraftMut: ReturnType<typeof useUpdateCodeDraft>
}) {
  const { strategyId, draftCodeId, draftChangelog, updateDraftMut } = opts

  const [saveStatus, setSaveStatus] = useState<'saved' | 'saving' | 'dirty'>('saved')
  // 倒计时显示(null=不显；dirty 时 3→2→1，由 setInterval 驱动，saveTimer 触发实际保存)
  const [countdown, setCountdown] = useState<number | null>(null)
  const codeRef = useRef<string>('')
  const saveTimerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  // 倒计时显示 timer(仅 setState 显示，不触发保存);deadlineRef 算剩余；lastShownRef 只在秒变时 setState 省渲染
  const countdownTimerRef = useRef<ReturnType<typeof setInterval> | undefined>(undefined)
  const deadlineRef = useRef(0)
  const lastShownRef = useRef<number | null>(null)
  // Cmd+S 用：最新可保存参数 + 当前 saveStatus(ref 防 stale closure,keydown handler [] 依赖读最新)
  const saveableRef = useRef<{ strategyId: number; codeId: number; changelog: string } | null>(null)
  const saveStatusRef = useRef<'saved' | 'saving' | 'dirty'>('saved')
  // doSave ref:keydown handler [] 依赖调最新闭包(防 stale，与 useWsTopic handlerRef 模式一致)
  const doSaveRef = useRef<(strategyId: number, codeId: number, changelog: string) => void>(() => {})

  // unmount 清理 save/countdown timer(防泄露)
  useEffect(() => {
    return () => {
      clearSaveTimers()
    }
  }, [])
  // saveStatus 同步到 ref(Cmd+S keydown handler [] 依赖读最新，防 stale closure)
  useEffect(() => {
    saveStatusRef.current = saveStatus
  }, [saveStatus])
  // Cmd+S/Ctrl+S:阻止浏览器保存网页默认 + dirty 时立即保存(跳过 3s debounce)
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 's') {
        e.preventDefault()
        const p = saveableRef.current
        if (p && saveStatusRef.current === 'dirty') {
          doSaveRef.current(p.strategyId, p.codeId, p.changelog)
        }
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  /** 清 save + countdown timer(集中清理点:unmount/resetAutoSave/新编辑/保存触发都调)。 */
  function clearSaveTimers() {
    if (saveTimerRef.current) {
      clearTimeout(saveTimerRef.current)
      saveTimerRef.current = undefined
    }
    if (countdownTimerRef.current) {
      clearInterval(countdownTimerRef.current)
      countdownTimerRef.current = undefined
    }
  }

  /** 实际保存：清 timer + saving 态 + 调 updateDraftMut；成功 saved / 失败 dirty + toast。 */
  function doSave(sid: number, codeId: number, changelog: string) {
    clearSaveTimers()
    setSaveStatus('saving')
    setCountdown(null)
    updateDraftMut.mutate(
      { strategyId: sid, codeId, req: { sourceCode: codeRef.current, changelog } },
      {
        onSuccess: () => {
          setSaveStatus('saved')
          setCountdown(null)
        },
        onError: () => {
          setSaveStatus('dirty')
          toast.error('自动保存失败')
        },
      },
    )
  }
  // 每 render 同步 doSave 到 ref(Cmd+S keydown [] 依赖调最新闭包，防 stale)
  doSaveRef.current = doSave

  function handleCodeChange(val: string | undefined) {
    codeRef.current = val ?? ''
    setSaveStatus('dirty')
    clearSaveTimers() // 清旧 timer 真 debounce(防多次编辑堆积多个 timer)
    if (strategyId == null || draftCodeId == null) return
    const changelog = draftChangelog
    // Cmd+S 用：存最新可保存参数(ref 防 stale closure)
    saveableRef.current = { strategyId, codeId: draftCodeId, changelog }
    // 双 timer:setTimeout 兜底准时触发保存(后台 tab setInterval 被节流也不漏保存);
    // setInterval 仅更新倒计时显示(只在秒变时 setState 省渲染)
    deadlineRef.current = Date.now() + 3000
    setCountdown(3)
    lastShownRef.current = 3
    countdownTimerRef.current = setInterval(() => {
      const remain = Math.ceil((deadlineRef.current - Date.now()) / 1000)
      if (remain <= 0) return // 保存由 saveTimer 触发，tick 不重复
      if (remain !== lastShownRef.current) {
        lastShownRef.current = remain
        setCountdown(remain)
      }
    }, 250)
    saveTimerRef.current = setTimeout(() => doSave(strategyId, draftCodeId, changelog), 3000)
  }

  /** 切换策略/删草稿/创建策略时调：清 pending 自动保存 timer + codeRef，防旧 timer 用新代码污染旧策略草稿(B-1)。 */
  function resetAutoSave() {
    clearSaveTimers()
    saveableRef.current = null
    lastShownRef.current = null
    setCountdown(null)
    codeRef.current = ''
    setSaveStatus('saved')
  }

  /** 取消 pending debounce 保存(不清 codeRef/状态)——发布前调，防 debounce 保存与发布 updateDraft race。 */
  function cancelPendingSave() {
    clearSaveTimers()
  }

  return { saveStatus, countdown, codeRef, handleCodeChange, resetAutoSave, cancelPendingSave }
}
