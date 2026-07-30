import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { useCreateLlmKey, useUpdateLlmKey, useTestLlmKey } from '@/hooks/useSettings'
import { type LlmProvider, type LlmApiKeyView } from '@/api/ai'
import { candidateModels } from '@/api/llm-models'

/**
 * AddLlmKeyDialog — LLM API 密钥添加/编辑 modal(共享组件)。
 *
 * 双态:`editingKey` 非空=编辑(provider 锁定 + 预填,apiKey 留空=不改)/ 空=添加。
 * SettingsPage(llm tab)+ SessionPanel(会话栏空态快速配置)共用,避免两处配置入口重复(DRY)。
 *
 * apiKey 留空=不改密钥(编辑态);provider 不可改(换 provider 删了重建)。
 * 「保存并测试」:create/update 成功后用首个 model 测连通(后端 ping + sanitize 脱敏)。
 * 成功后 `onOpenChange(false)` 关闭 + react-query invalidate aiKeys 自动刷新调用方列表
 * (SessionPanel 会话栏 combobox 选项随之更新)。
 *
 * 预填:lazy initializer(mount 时按 editingKey 预填);调用方条件渲染 `{showAddLlm && <AddLlmKeyDialog>}`,
 * 每次 open 重新 mount → 重新预填,关闭 unmount → state 清;避免 useEffect 同步 setState(set-state-in-effect)。
 */
const PROVIDER_OPTIONS: { value: LlmProvider; label: string }[] = [
  { value: 'OPENAI', label: 'OpenAI' },
  { value: 'ANTHROPIC', label: 'Anthropic' },
  { value: 'OPENAI_COMPATIBLE', label: 'OpenAI 兼容 (DeepSeek 等)' },
]

interface AddLlmKeyDialogProps {
  open: boolean
  onOpenChange: (v: boolean) => void
  /** 非空=编辑态(预填该 key,provider 锁定,apiKey 留空=不改);空=添加态 */
  editingKey?: LlmApiKeyView | null
  /** 精简模式(策略页会话栏用):隐藏 provider/标签/apiKey/baseUrl,显密钥 Select + model 区,
   *  给指定 key 加/删模型;provider/标签/密钥不变,保存只更新 available_models。要求 editingKey 非空。 */
  compact?: boolean
  /** compact 模式用:密钥列表(密钥 Select 切管理 key) */
  llmKeys?: LlmApiKeyView[]
  /** compact 模式用:密钥 Select 切 key 回调(联动调用方 activeKey;key prop remount → lazy init 预填新 key models) */
  onKeyChange?: (id: number) => void
}

export function AddLlmKeyDialog({
  open,
  onOpenChange,
  editingKey,
  compact = false,
  llmKeys,
  onKeyChange,
}: AddLlmKeyDialogProps) {
  const navigate = useNavigate()
  const createLlmMut = useCreateLlmKey()
  const updateLlmMut = useUpdateLlmKey()
  const testLlmMut = useTestLlmKey()

  const [llmProvider, setLlmProvider] = useState<LlmProvider>(() => editingKey?.provider ?? 'OPENAI')
  const [llmLabel, setLlmLabel] = useState(() => editingKey?.label ?? '')
  const [llmApiKey, setLlmApiKey] = useState('')
  const [llmBaseUrl, setLlmBaseUrl] = useState(() => editingKey?.baseUrl ?? '')
  const [llmModels, setLlmModels] = useState<string[]>(() => editingKey?.availableModels ?? [])
  const [llmCustomModel, setLlmCustomModel] = useState('')


  /** 「保存并测试」:用首个 model 测连通性(后端 ping + sanitize 脱敏)。create/update onSuccess 共用(DRY)。 */
  function testAfterSave(id: number | null | undefined, model: string | undefined) {
    if (id == null || !model) return
    testLlmMut.mutate(
      { id, model },
      {
        onSuccess: (r) => {
          if (r.success) {
            toast.success('连通性正常', { description: `${model} 可用` })
          } else {
            toast.error('连通失败', { description: r.message })
          }
        },
        onError: () => toast.error('连通测试失败,请重试'),
      },
    )
  }

  function handleCreateLlm() {
    if (!llmLabel.trim() || !llmApiKey.trim()) {
      toast.warning('请填写标签与 API 密钥')
      return
    }
    if (llmProvider === 'OPENAI_COMPATIBLE' && !llmBaseUrl.trim()) {
      toast.warning('OpenAI 兼容服务商必须填接口地址')
      return
    }
    if (llmProvider === 'OPENAI_COMPATIBLE' && llmModels.length === 0) {
      toast.warning('OpenAI 兼容服务商必须添加至少一个模型')
      return
    }
    const firstModel = llmModels[0]
    createLlmMut.mutate(
      {
        label: llmLabel.trim(),
        provider: llmProvider,
        apiKey: llmApiKey.trim(),
        baseUrl: llmBaseUrl.trim(),
        availableModels: llmModels,
      },
      {
        onSuccess: (created) => {
          toast.success('API 密钥已加密保存,仅显示末 4 位')
          onOpenChange(false)
          // 「保存并测试」:用首个 model 测连通性(后端 ping + sanitize 脱敏)
          testAfterSave(created.id, firstModel)
        },
        onError: () => toast.error('保存失败,请重试'),
      },
    )
  }

  function handleUpdateLlm() {
    if (!editingKey) return
    if (!llmLabel.trim()) {
      toast.warning('请填写标签')
      return
    }
    if (llmProvider === 'OPENAI_COMPATIBLE' && !llmBaseUrl.trim()) {
      toast.warning('OpenAI 兼容服务商必须填接口地址')
      return
    }
    if (llmProvider === 'OPENAI_COMPATIBLE' && llmModels.length === 0) {
      toast.warning('OpenAI 兼容服务商必须添加至少一个模型')
      return
    }
    const firstModel = llmModels[0]
    updateLlmMut.mutate(
      {
        id: editingKey.id,
        req: {
          label: llmLabel.trim(),
          apiKey: llmApiKey.trim(),
          baseUrl: llmBaseUrl.trim(),
          availableModels: llmModels,
        },
      },
      {
        onSuccess: (updated) => {
          toast.success('已更新')
          onOpenChange(false)
          // 编辑后测连通(用首个 model;apiKey 未改也可测,验证配置仍可用)
          testAfterSave(updated.id, firstModel)
        },
        onError: () => toast.error('更新失败,请重试'),
      },
    )
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-[480px]">
        <DialogHeader>
          <DialogTitle>{compact ? '管理模型' : editingKey ? '编辑 AI 密钥' : '添加 AI 密钥'}</DialogTitle>
          <DialogDescription>
            {compact
              ? '给当前密钥加/删模型(provider/标签/密钥不变)。'
              : editingKey
                ? '修改标签、模型或轮换密钥(API 密钥留空保持原密钥不变)。'
                : '加密存储,仅显示末 4 位明文。'}
          </DialogDescription>
        </DialogHeader>
        <div className="flex flex-col gap-3">
          {!compact && (
            <>
              <div>
                <Label className="kq-label">服务商</Label>
            <Select
              value={llmProvider}
              onValueChange={(v) => setLlmProvider(v as LlmProvider)}
              disabled={!!editingKey}
            >
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {PROVIDER_OPTIONS.map((o) => (
                  <SelectItem key={o.value} value={o.value}>
                    {o.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            {editingKey && (
              <p className="text-[10px] text-text-muted">
                服务商创建后不可修改,如需更换请删除后新建
              </p>
            )}
          </div>
          <div>
            <Label className="kq-label">标签</Label>
            <Input
              placeholder="例:gpt-5 风格策略"
              value={llmLabel}
              onChange={(e) => setLlmLabel(e.target.value)}
            />
          </div>
          <div>
            <Label className="kq-label">API 密钥</Label>
            <Input
              type="password"
              placeholder={editingKey ? '留空保持原密钥不变' : 'sk-...'}
              value={llmApiKey}
              onChange={(e) => setLlmApiKey(e.target.value)}
            />
          </div>
          {llmProvider === 'OPENAI_COMPATIBLE' && (
            <div>
              <Label className="kq-label">接口地址(必填)</Label>
              <Input
                placeholder="https://api.example.com/v1"
                value={llmBaseUrl}
                onChange={(e) => setLlmBaseUrl(e.target.value)}
              />
            </div>
          )}
            </>
          )}
          {compact && llmKeys && (
            <div>
              <Label className="kq-label">密钥</Label>
              <Select
                value={String(editingKey?.id ?? '')}
                onValueChange={(v) => {
                  if (v === '__add__') {
                    // 跳转设置页添加新 key(完整 provider/apiKey/baseUrl 在设置页更合适,compact 只管 model)
                    onOpenChange(false)
                    navigate('/settings?tab=llm')
                    return
                  }
                  onKeyChange?.(parseInt(v, 10))
                }}
              >
                <SelectTrigger>
                  <SelectValue placeholder="选择密钥" />
                </SelectTrigger>
                <SelectContent>
                  {llmKeys.map((k) => (
                    <SelectItem key={k.id} value={String(k.id)}>
                      {k.label}
                    </SelectItem>
                  ))}
                  <SelectItem value="__add__">+ 添加新密钥(设置页)</SelectItem>
                </SelectContent>
              </Select>
            </div>
          )}
          <div>
            <Label className="kq-label">
              模型{llmProvider === 'OPENAI_COMPATIBLE' ? '(必填 ≥1)' : '(可选,留空则使用服务商默认)'}
            </Label>
            {llmProvider === 'OPENAI_COMPATIBLE' && (
              <p className="mt-0.5 text-[10px] leading-relaxed text-text-muted">
                模型名需与服务商文档完全一致;OpenRouter 格式为 owner/model:variant(如 nvidia/nemotron-3-ultra-550b-a55b:free,漏 owner 前缀会被拒)
              </p>
            )}
            {/* 已选模型 chip 列表(可删) */}
            {llmModels.length > 0 && (
              <div className="mt-1.5 flex flex-wrap gap-1.5">
                {llmModels.map((m) => (
                  <button
                    key={m}
                    type="button"
                    onClick={() => setLlmModels(llmModels.filter((x) => x !== m))}
                    className="kq-mono-row rounded-full border border-border-soft bg-surface-card-2 px-2 py-0.5 text-[11px] text-text-primary hover:bg-surface-3"
                  >
                    {m} ✕
                  </button>
                ))}
              </div>
            )}
            {/* 预置库快捷按钮(按 provider;已选的不重复显示) */}
            <div className="mt-1.5 flex flex-wrap gap-1">
              {candidateModels(llmProvider)
                .filter((m) => !llmModels.includes(m))
                .slice(0, 6)
                .map((m) => (
                  <button
                    key={m}
                    type="button"
                    onClick={() => setLlmModels([...llmModels, m])}
                    className="kq-mono-row rounded-md border border-dashed border-border-soft px-1.5 py-0.5 text-[11px] text-text-secondary hover:bg-surface-card-2"
                  >
                    + {m}
                  </button>
                ))}
            </div>
            {/* 自定义模型名(Enter 添加) */}
            <Input
              placeholder="自定义模型名,Enter 添加"
              value={llmCustomModel}
              onChange={(e) => setLlmCustomModel(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && llmCustomModel.trim()) {
                  e.preventDefault()
                  const v = llmCustomModel.trim()
                  if (!llmModels.includes(v)) setLlmModels([...llmModels, v])
                  setLlmCustomModel('')
                }
              }}
              className="mt-1.5"
            />
          </div>
          {!compact && (
            <div className="rounded-md border border-dashed border-border-soft bg-surface-card-2 p-2.5 text-[11px] leading-relaxed text-text-muted">
              ⚠ API 密钥加密存储,不会完整显示。
            </div>
          )}
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            取消
          </Button>
          <Button
            onClick={editingKey ? handleUpdateLlm : handleCreateLlm}
            disabled={createLlmMut.isPending || updateLlmMut.isPending}
          >
            {(editingKey ? updateLlmMut.isPending : createLlmMut.isPending) ? '保存中…' : '保存'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
