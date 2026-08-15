import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Bell, Copy, KeyRound, Plus, ShieldAlert, Trash2, User, Wallet } from 'lucide-react'
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
import { Card } from '@/components/ui/card'
import { Chip } from '@/components/Chip'
import { SectionTitle } from '@/components/SectionTitle'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { EmptyState } from '@/components/EmptyState'
import { ErrorState } from '@/components/ErrorState'
import { LoadingState } from '@/components/feedback/LoadingState'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Checkbox } from '@/components/ui/checkbox'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  useLlmKeys,
  useCreateLlmKey,
  useDeleteLlmKey,
  useTestLlmKey,
  useMcpTokens,
  useIssueMcpToken,
  useRevokeMcpToken,
  useNotifPrefs,
  useUpsertNotifPrefs,
  useChangePassword,
} from '@/hooks/useSettings'
import { providerLabel, type LlmProvider } from '@/api/ai'
import { candidateModels } from '@/api/llm-models'
import {
  NOTIF_CHANNEL_TYPES,
  NOTIF_EVENT_TYPES,
  channelTypeLabel,
  eventTypeLabel,
} from '@/api/notification'
import { formatDateTime } from '@/lib/format'
import { ApiError } from '@/lib/http'
import type { components } from '@/types/api-gen'
import { AccountCard } from '@/components/AccountCard'
import { AddAccountDialog } from '@/components/AddAccountDialog'
import {
  useAccounts,
  useCreateAccount,
  useDeleteAccount,
  useResetPaperAccount,
} from '@/hooks/useAccounts'

/**
 * SettingsPage — 设置页(照 prototypes/done-design/components/SettingsPage.jsx port)。
 * 4 tab(LLM API Key / MCP 令牌 / 通知偏好 / 账户与密码)+ 3 modal(AddLlm / AddMcp / McpReveal)
 * + 2 破坏性 ConfirmDialog(删 LLM key / 吊销 MCP token)。
 *
 * 与原型差异(适配后端契约;会话吊销/密钥轮换无后端端点,已删 UI):
 *  - LlmApiKeyView 无 active 字段 → 不展"启用"徽章
 *  - McpTokenView 无 scopes 字段 → 签发 modal scopes 勾选 UI 保留但不传后端(CreateMcpTokenRequest 只要 name);列表卡不展 scopes
 *  - McpTokenView 不含明文 token(明文仅 issue 响应 one-time)→ 列表卡永久 masked,移除原型 show/hide toggle
 *  - telegram/webhook 渠道后端暂未支持 → UI 保留 4 渠道,PUT 只传 WEBSOCKET/EMAIL
 *  - provider 枚举 → 中文映射
 *  - auth.ts api 模块只含 changePassword,login/register/refresh 仍在 hooks 裸调
 */

// 通知矩阵默认值(原型 EVENT_TYPES.def × CHANNELS.def;无记录 = 默认推送)
const EVENT_DEFAULTS: Record<string, boolean> = {
  RISK_REJECTED: true,
  ORDER_FILLED: true,
  ORDER_CANCELLED: true,
  STRATEGY_STARTED: true,
  STRATEGY_STOPPED: false,
  STRATEGY_ERROR: true,
}
const CHANNEL_DEFAULTS: Record<string, boolean> = {
  WEBSOCKET: true,
  EMAIL: false,
  TELEGRAM: false,
  WEBHOOK: false,
}

// MCP 签发 scope 真实生效(后端 McpScopeGuard 校验):5 档粗粒度,默认仅 READ(最小权限),
// 写/高危显式勾选。高危写操作另走两阶段 confirmToken,scope 与确认两层独立。
import {
  HIGH_RISK_SCOPES,
  MCP_SCOPE_LABELS,
  MCP_SCOPES,
  type McpScope,
} from '@/api/mcp'

/** LLM provider select 选项(契约枚举 3 个)。 */
const PROVIDER_OPTIONS: { value: LlmProvider; label: string }[] = [
  { value: 'OPENAI', label: 'OpenAI' },
  { value: 'ANTHROPIC', label: 'Anthropic' },
  { value: 'OPENAI_COMPATIBLE', label: 'OpenAI 兼容 (DeepSeek 等)' },
]

// ─── 主页 ───
type ExchangeAccountView = components['schemas']['ExchangeAccountView']

export function SettingsPage() {
  const [searchParams] = useSearchParams()
  const [tab, setTab] = useState(searchParams.get('tab') ?? 'llm')

  // LLM keys
  const { data: llmKeys, isLoading: llmLoading, error: llmError } = useLlmKeys()
  const createLlmMut = useCreateLlmKey()
  const deleteLlmMut = useDeleteLlmKey()
  const testLlmMut = useTestLlmKey()

  // MCP tokens
  const { data: mcpTokens, isLoading: mcpLoading, error: mcpError } = useMcpTokens()
  const issueMcpMut = useIssueMcpToken()
  const revokeMcpMut = useRevokeMcpToken()

  // 通知偏好
  const { data: notifPrefs } = useNotifPrefs()
  const upsertNotifMut = useUpsertNotifPrefs()

  // 改密码
  const changePwdMut = useChangePassword()

  // 交易账户(账户管理从 PortfolioPage 搬入)
  const { data: accounts, isLoading: accLoading, error: accError } = useAccounts()
  const createAccMut = useCreateAccount()
  const deleteAccMut = useDeleteAccount()
  const resetAccMut = useResetPaperAccount()

  // modal 开关
  const [showAddLlm, setShowAddLlm] = useState(false)
  const [showAddMcp, setShowAddMcp] = useState(false)
  const [mcpRevealToken, setMcpRevealToken] = useState<string | null>(null)

  // AddLlm 表单
  const [llmLabel, setLlmLabel] = useState('')
  const [llmProvider, setLlmProvider] = useState<LlmProvider>('OPENAI')
  const [llmApiKey, setLlmApiKey] = useState('')
  const [llmBaseUrl, setLlmBaseUrl] = useState('')
  const [llmModels, setLlmModels] = useState<string[]>([])
  const [llmCustomModel, setLlmCustomModel] = useState('')

  // AddMcp 表单
  const [mcpName, setMcpName] = useState('我的 AI 助手')
  const [mcpScopes, setMcpScopes] = useState<Set<McpScope>>(() => new Set([MCP_SCOPES[0]]))

  // 改密码表单
  const [oldPassword, setOldPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')

  // 破坏性 Confirm 目标
  const [deleteLlmTarget, setDeleteLlmTarget] =
    useState<import('@/api/ai').LlmApiKeyView | null>(null)
  const [revokeMcpTarget, setRevokeMcpTarget] =
    useState<import('@/api/mcp').McpTokenView | null>(null)

  // 交易账户破坏性 Confirm 目标(删除/重置)
  const [showAddAcc, setShowAddAcc] = useState(false)
  const [deleteAccTarget, setDeleteAccTarget] = useState<ExchangeAccountView | null>(null)
  const [resetAccTarget, setResetAccTarget] = useState<ExchangeAccountView | null>(null)

  // 通知矩阵:default × GET prefs,localOverrides 派生乐观态(PUT 成功 refetch 后匹配)
  const notifMatrix = useMemo(() => {
    const m: Record<string, boolean> = {}
    for (const ev of NOTIF_EVENT_TYPES) {
      for (const ch of NOTIF_CHANNEL_TYPES) {
        m[`${ev}:${ch}`] = EVENT_DEFAULTS[ev] && CHANNEL_DEFAULTS[ch]
      }
    }
    for (const p of notifPrefs ?? []) {
      m[`${p.eventType}:${p.channelType}`] = p.enabled
    }
    return m
  }, [notifPrefs])
  const [localOverrides, setLocalOverrides] = useState<Record<string, boolean>>({})
  const effectiveMatrix = useMemo(
    () => ({ ...notifMatrix, ...localOverrides }),
    [notifMatrix, localOverrides],
  )

  // ─── handlers ───

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
          setShowAddLlm(false)
          setLlmLabel('')
          setLlmApiKey('')
          setLlmBaseUrl('')
          setLlmModels([])
          setLlmCustomModel('')
          // 「保存并测试」:用首个 model 测连通性(后端 ping + sanitize 脱敏)
          if (created.id != null && firstModel) {
            testLlmMut.mutate(
              { id: created.id, model: firstModel },
              {
                onSuccess: (r) => {
                  if (r.success) {
                    toast.success('连通性正常', { description: `${firstModel} 可用` })
                  } else {
                    toast.error('连通失败', { description: r.message })
                  }
                },
                onError: () => toast.error('连通测试失败,请重试'),
              },
            )
          }
        },
        onError: () => toast.error('保存失败,请重试'),
      },
    )
  }

  function handleIssueMcp() {
    if (!mcpName.trim()) {
      toast.warning('请填写助手名称')
      return
    }
    // scopes/expiresInDays 真实传后端(默认 90 天);至少保留 READ
    const scopes = mcpScopes.size > 0 ? Array.from(mcpScopes) : [MCP_SCOPES[0]]
    issueMcpMut.mutate(
      { name: mcpName.trim(), scopes, expiresInDays: 90 },
      {
        onSuccess: (result) => {
          toast.success('MCP 令牌已签发')
          setShowAddMcp(false)
          setMcpRevealToken(result.token)
          setMcpName('我的 AI 助手')
          setMcpScopes(new Set([MCP_SCOPES[0]]))
        },
        onError: () => toast.error('签发失败,请重试'),
      },
    )
  }

  function handleCopyToken(token: string) {
    if (navigator.clipboard) {
      navigator.clipboard.writeText(token)
      toast.success('已复制到剪贴板')
    }
  }

  function handleDeleteLlm() {
    if (!deleteLlmTarget) return
    deleteLlmMut.mutate(deleteLlmTarget.id, {
      onSuccess: () => {
        toast.success('已删除', { description: deleteLlmTarget.label })
        setDeleteLlmTarget(null)
      },
      onError: () => toast.error('删除失败,请重试'),
    })
  }

  function handleRevokeMcp() {
    if (!revokeMcpTarget) return
    revokeMcpMut.mutate(revokeMcpTarget.id, {
      onSuccess: () => {
        toast.success('令牌已吊销', { description: revokeMcpTarget.name })
        setRevokeMcpTarget(null)
      },
      onError: () => toast.error('吊销失败,请重试'),
    })
  }

  function handleNotifToggle(ev: string, ch: string) {
    // telegram/webhook 渠道后端暂未支持,toggle 提示暂未接入,不乐观持久化
    if (ch !== 'WEBSOCKET' && ch !== 'EMAIL') {
      toast.info(`${channelTypeLabel(ch)} 渠道暂未接入,敬请期待`)
      return
    }
    const key = `${ev}:${ch}`
    const newVal = !effectiveMatrix[key]
    setLocalOverrides((prev) => ({ ...prev, [key]: newVal }))
    toast.success(`${eventTypeLabel(ev)} / ${channelTypeLabel(ch)} 已${newVal ? '启用' : '关闭'}`)
    upsertNotifMut.mutate({
      preferences: [{ eventType: ev, channelType: ch, enabled: newVal }],
    })
  }

  function handleChangePassword() {
    if (!oldPassword || !newPassword || !confirmPassword) {
      toast.warning('请填写全部密码字段')
      return
    }
    if (newPassword.length < 8 || newPassword.length > 128) {
      toast.warning('新密码需 8-128 字符')
      return
    }
    if (newPassword !== confirmPassword) {
      toast.warning('两次输入的新密码不一致')
      return
    }
    changePwdMut.mutate(
      { oldPassword, newPassword },
      {
        onSuccess: () => {
          toast.success('密码已更新')
          setOldPassword('')
          setNewPassword('')
          setConfirmPassword('')
        },
        onError: (e: Error) => {
          // 旧密码错 401 1001(见 behavior-contract.md 错误码映射);isUnauthorized getter 已含 code===1001||status===401
          if (e instanceof ApiError && e.isUnauthorized) {
            toast.error('旧密码错误')
          } else {
            toast.error('更新失败,请重试')
          }
        },
      },
    )
  }

  return (
    <div className="flex flex-col gap-4.5">
      {/* Header */}
      <div>
        <h1 className="text-h1 font-bold tracking-[-0.015em] text-text-primary">设置</h1>
        <p className="mt-1.5 text-body-sm text-text-secondary">
          管理 AI 密钥 · MCP 令牌 · 通知偏好 · 密码
        </p>
      </div>

      <Tabs value={tab} onValueChange={setTab} className="gap-4.5">
        <TabsList className="bg-transparent p-0 h-auto border-b border-border-soft rounded-none">
          <TabsTrigger value="llm" className="gap-1.5">
            <KeyRound className="size-3.5" aria-hidden />
            AI 密钥
          </TabsTrigger>
          <TabsTrigger value="mcp" className="gap-1.5">
            <ShieldAlert className="size-3.5" aria-hidden />
            MCP 令牌
          </TabsTrigger>
          <TabsTrigger value="notif" className="gap-1.5">
            <Bell className="size-3.5" aria-hidden />
            通知偏好
          </TabsTrigger>
          <TabsTrigger value="accounts" className="gap-1.5">
            <Wallet className="size-3.5" aria-hidden />
            交易账户
          </TabsTrigger>
          <TabsTrigger value="account" className="gap-1.5">
            <User className="size-3.5" aria-hidden />
            账户与密码
          </TabsTrigger>
        </TabsList>

        {/* ─── LLM tab ─── */}
        <TabsContent value="llm" className="mt-0">
          <div className="flex flex-col gap-3">
            <SectionTitle
              title="AI 密钥"
              sub="多服务商 · 加密存储 · 仅显示末 4 位"
              right={
                <Button onClick={() => setShowAddLlm(true)} size="sm">
                  <Plus className="size-3.5" aria-hidden />
                  添加密钥
                </Button>
              }
            />
            {llmError ? (
              <ErrorState />
            ) : llmLoading ? (
              <LoadingState />
            ) : !llmKeys || llmKeys.length === 0 ? (
              <EmptyState title="暂无 AI 密钥" description="添加第一个 API 密钥开始使用 AI 对话。" />
            ) : (
              <div className="flex flex-col gap-3">
                {llmKeys.map((k) => (
                  <Card key={k.id} className="p-4">
                    <div className="flex items-start justify-between gap-3">
                      <div className="flex-1">
                        <div className="flex items-center gap-2">
                          <strong className="text-sm font-semibold text-text-primary">
                            {k.label}
                          </strong>
                          <Chip
                            color={k.provider === 'OPENAI' ? 'info' : 'accent'}
                            label={providerLabel(k.provider)}
                          />
                        </div>
                        <div className="mt-2 flex gap-2.5 text-body-sm text-text-muted">
                          <span>
                            密钥{' '}
                            <span className="kq-mono-row text-text-secondary">
                              {k.apiKeyMasked}
                            </span>
                          </span>
                          <span>添加于 {formatDateTime(k.createdAt)}</span>
                        </div>
                        {k.availableModels.length > 0 && (
                          <div className="mt-1.5 flex flex-wrap gap-1">
                            {k.availableModels.map((m) => (
                              <span
                                key={m}
                                className="kq-mono-row rounded border border-border-soft bg-surface-card-2 px-1.5 py-0.5 text-caption-sm text-text-secondary"
                              >
                                {m}
                              </span>
                            ))}
                          </div>
                        )}
                      </div>
                      <div className="flex gap-1.5">
                        <Button
                          variant="ghost"
                          size="sm"
                          disabled={testLlmMut.isPending || k.availableModels.length === 0}
                          onClick={() => {
                            const m = k.availableModels[0]
                            if (!m) {
                              toast.warning('该密钥未配置模型,无法测试')
                              return
                            }
                            testLlmMut.mutate(
                              { id: k.id, model: m },
                              {
                                onSuccess: (r) => {
                                  if (r.success) {
                                    toast.success('连通性正常', { description: `${m} 可用` })
                                  } else {
                                    toast.error('连通失败', { description: r.message })
                                  }
                                },
                                onError: () => toast.error('连通测试失败,请重试'),
                              },
                            )
                          }}
                        >
                          测试连通
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          className="text-down hover:text-down"
                          onClick={() => setDeleteLlmTarget(k)}
                        >
                          <Trash2 className="size-3.5" aria-hidden />
                          删除
                        </Button>
                      </div>
                    </div>
                  </Card>
                ))}
              </div>
            )}
          </div>
        </TabsContent>

        {/* ─── MCP tab ─── */}
        <TabsContent value="mcp" className="mt-0">
          <div className="flex flex-col gap-3">
            <SectionTitle
              title="MCP 令牌"
              sub="供 AI 助手使用 · 明文仅签发时显示一次"
              right={
                <Button onClick={() => setShowAddMcp(true)} size="sm">
                  <Plus className="size-3.5" aria-hidden />
                  签发令牌
                </Button>
              }
            />
            <Card className="border-accent bg-accent-soft p-3.5">
              <div className="flex items-start gap-3">
                <div className="flex size-8 shrink-0 items-center justify-center rounded-md bg-accent font-bold text-on-accent">
                  AI
                </div>
                <div className="text-body-sm text-text-primary leading-relaxed">
                  <strong>MCP 助手能代你</strong> · 查询账户 / 查看行情 / 下单 / 撤单 / 查看持仓 / 执行回测 / 启停策略。<strong>紧急停止、启动实盘</strong>等高风险操作,会要求再次确认。
                </div>
              </div>
            </Card>
            {mcpError ? (
              <ErrorState />
            ) : mcpLoading ? (
              <LoadingState />
            ) : !mcpTokens || mcpTokens.length === 0 ? (
              <EmptyState title="暂无 MCP 令牌" description="签发令牌供 AI 助手使用。" />
            ) : (
              <div className="flex flex-col gap-3">
                {mcpTokens.map((t) => (
                  <Card key={t.id} className="p-4">
                    <div className="flex items-start justify-between gap-3">
                      <div className="flex-1">
                        <div className="flex items-center gap-2">
                          <strong className="text-sm font-semibold text-text-primary">
                            {t.name}
                          </strong>
                          {!t.revokedAt && <Chip color="up" label="有效" />}
                        </div>
                        <div className="mt-2 rounded-md border border-border-soft bg-surface-card-2 p-2.5 text-body-sm text-text-secondary">
                          <div className="flex items-center justify-between gap-2">
                            <span>访问令牌</span>
                            <span className="kq-mono-row">
                              kq_pat_••••••••••••••••••••••••••••••
                            </span>
                          </div>
                          <div className="mt-1 text-caption-sm text-text-muted">
                            明文仅签发时显示一次,此后无法再次查看
                          </div>
                        </div>
                        <div className="mt-1.5 text-caption-sm text-text-muted">
                          创建 {formatDateTime(t.createdAt)} · 上次使用{' '}
                          {t.lastUsedAt ? formatDateTime(t.lastUsedAt) : '从未使用'}
                        </div>
                      </div>
                      <Button
                        variant="ghost"
                        size="sm"
                        className="text-down hover:text-down"
                        onClick={() => setRevokeMcpTarget(t)}
                      >
                        <Trash2 className="size-3.5" aria-hidden />
                        吊销
                      </Button>
                    </div>
                  </Card>
                ))}
              </div>
            )}
          </div>
        </TabsContent>

        {/* ─── Notif tab ─── */}
        <TabsContent value="notif" className="mt-0">
          <div className="flex flex-col gap-3">
            <SectionTitle
              title="通知偏好"
              sub="按事件类型与通知渠道开关"
            />
            <Card className="overflow-hidden p-0">
              <table className="w-full text-body-sm">
                <thead>
                  <tr className="text-left text-caption-sm uppercase tracking-[0.04em] text-text-muted">
                    <th className="border-b border-border-soft px-4 py-3">事件类型</th>
                    {NOTIF_CHANNEL_TYPES.map((c) => (
                      <th
                        key={c}
                        className="border-b border-border-soft px-4 py-3 text-center"
                      >
                        {channelTypeLabel(c)}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {NOTIF_EVENT_TYPES.map((ev) => (
                    <tr key={ev}>
                      <td className="border-b border-border-soft px-4 py-3 font-semibold text-text-primary">
                        {eventTypeLabel(ev)}
                      </td>
                      {NOTIF_CHANNEL_TYPES.map((ch) => (
                        <td
                          key={ch}
                          className="border-b border-border-soft px-4 py-3 text-center"
                        >
                          <Checkbox
                            checked={!!effectiveMatrix[`${ev}:${ch}`]}
                            onCheckedChange={() => handleNotifToggle(ev, ch)}
                            disabled={ch !== 'WEBSOCKET' && ch !== 'EMAIL'}
                            aria-label={`${eventTypeLabel(ev)} / ${channelTypeLabel(ch)}`}
                            className="scale-[1.3]"
                          />
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </Card>
          </div>
        </TabsContent>

        {/* ─── 交易账户 tab(从 PortfolioPage 搬入,managed 态 AccountCard) ─── */}
        <TabsContent value="accounts" className="mt-0">
          <div className="flex flex-col gap-3">
            <SectionTitle
              title="交易账户"
              sub="API key 加密存储 · 仅露末 4 位"
              right={
                <Button onClick={() => setShowAddAcc(true)} size="sm">
                  <Plus className="size-3.5" aria-hidden />
                  添加账户
                </Button>
              }
            />
            {accError ? (
              <ErrorState />
            ) : accLoading ? (
              <LoadingState />
            ) : !accounts || accounts.length === 0 ? (
              <EmptyState title="还没有交易账户" description="添加模拟盘开始试策略,或接入实盘账户。" />
            ) : (
              <div className="grid grid-cols-3 gap-3.5 max-[1100px]:grid-cols-2 max-[680px]:grid-cols-1">
                {accounts.map((a) => (
                  <AccountCard
                    key={a.id}
                    acc={a}
                    onReset={() => setResetAccTarget(a)}
                    onDelete={() => setDeleteAccTarget(a)}
                  />
                ))}
              </div>
            )}
          </div>
        </TabsContent>

        {/* ─── Account tab ─── */}
        <TabsContent value="account" className="mt-0">
          <div className="flex flex-col gap-3.5">
            <SectionTitle title="账户与密码" sub="修改登录密码" />
            <Card className="max-w-[480px] p-4">
              <div className="flex flex-col gap-3.5">
                <div>
                  <Label htmlFor="cur-pwd" className="kq-label">
                    当前密码
                  </Label>
                  <Input
                    id="cur-pwd"
                    type="password"
                    value={oldPassword}
                    onChange={(e) => setOldPassword(e.target.value)}
                  />
                </div>
                <div>
                  <Label htmlFor="new-pwd" className="kq-label">
                    新密码
                  </Label>
                  <Input
                    id="new-pwd"
                    type="password"
                    placeholder="至少 8 位,含字母数字"
                    value={newPassword}
                    onChange={(e) => setNewPassword(e.target.value)}
                  />
                </div>
                <div>
                  <Label htmlFor="confirm-pwd" className="kq-label">
                    确认新密码
                  </Label>
                  <Input
                    id="confirm-pwd"
                    type="password"
                    placeholder="再输入一次"
                    value={confirmPassword}
                    onChange={(e) => setConfirmPassword(e.target.value)}
                  />
                </div>
                <Button
                  onClick={handleChangePassword}
                  disabled={changePwdMut.isPending}
                  className="self-start"
                  size="sm"
                >
                  {changePwdMut.isPending ? '更新中…' : '更新密码'}
                </Button>
              </div>
            </Card>
          </div>
        </TabsContent>
      </Tabs>

      {/* ─── Add LLM modal ─── */}
      <Dialog open={showAddLlm} onOpenChange={setShowAddLlm}>
        <DialogContent className="max-w-[480px]">
          <DialogHeader>
            <DialogTitle>添加 AI 密钥</DialogTitle>
            <DialogDescription>加密存储,仅显示末 4 位明文。</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-3">
            <div>
              <Label className="kq-label">服务商</Label>
              <Select value={llmProvider} onValueChange={(v) => setLlmProvider(v as LlmProvider)}>
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
                placeholder="sk-..."
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
            <div>
              <Label className="kq-label">
                模型{llmProvider === 'OPENAI_COMPATIBLE' ? '(必填 ≥1)' : '(可选,留空则使用服务商默认)'}
              </Label>
              {/* 已选模型 chip 列表(可删) */}
              {llmModels.length > 0 && (
                <div className="mt-1.5 flex flex-wrap gap-1.5">
                  {llmModels.map((m) => (
                    <button
                      key={m}
                      type="button"
                      onClick={() => setLlmModels(llmModels.filter((x) => x !== m))}
                      className="kq-mono-row rounded-full border border-border-soft bg-surface-card-2 px-2 py-0.5 text-caption-sm text-text-primary hover:bg-surface-3"
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
                      className="kq-mono-row rounded-md border border-dashed border-border-soft px-1.5 py-0.5 text-caption-sm text-text-secondary hover:bg-surface-card-2"
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
            <div className="rounded-md border border-dashed border-border-soft bg-surface-card-2 p-2.5 text-caption-sm leading-relaxed text-text-muted">
              ⚠ API 密钥加密存储,不会完整显示。
            </div>
          </div>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setShowAddLlm(false)}>
              取消
            </Button>
            <Button onClick={handleCreateLlm} disabled={createLlmMut.isPending}>
              {createLlmMut.isPending ? '保存中…' : '保存'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* ─── Add MCP modal ─── */}
      <Dialog open={showAddMcp} onOpenChange={setShowAddMcp}>
        <DialogContent className="max-w-[520px]">
          <DialogHeader>
            <DialogTitle>签发 MCP 令牌</DialogTitle>
            <DialogDescription>明文令牌仅签发时显示一次,关闭后无法再次查看。</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-3">
            <div>
              <Label className="kq-label">助手名称</Label>
              <Input value={mcpName} onChange={(e) => setMcpName(e.target.value)} />
            </div>
            <div>
              <Label className="kq-label">权限范围</Label>
              {/* scope 真实生效(后端 McpScopeGuard);默认仅 READ,写/高危显式勾选。
                  高危写操作另走两阶段 confirmToken,与 scope 是两层独立防护。 */}
              <div className="grid grid-cols-1 gap-1.5 text-body-sm">
                {MCP_SCOPES.map((s) => {
                  const checked = mcpScopes.has(s)
                  return (
                    <label
                      key={s}
                      className="flex items-center gap-1.5 rounded-md bg-surface-card-2 px-2.5 py-1.5"
                    >
                      <Checkbox
                        checked={checked}
                        onCheckedChange={(v) => {
                          setMcpScopes((prev) => {
                            const next = new Set(prev)
                            if (v) next.add(s)
                            else next.delete(s)
                            return next
                          })
                        }}
                      />
                      <span className="kq-mono-row text-caption-sm">{s}</span>
                      <span className="text-caption-sm text-text-secondary">{MCP_SCOPE_LABELS[s]}</span>
                      {HIGH_RISK_SCOPES.has(s) && (
                        <span className="text-caption-xs text-down">·高风险</span>
                      )}
                    </label>
                  )
                })}
              </div>
            </div>
            <div className="rounded-md border border-accent bg-accent-soft p-2.5 text-caption-sm leading-relaxed text-text-primary">
              ⚠ <strong>明文令牌仅签发时显示一次</strong>,关闭后无法再次查看。紧急停止、启动实盘等高风险操作会要求再次确认。
            </div>
          </div>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setShowAddMcp(false)}>
              取消
            </Button>
            <Button onClick={handleIssueMcp} disabled={issueMcpMut.isPending}>
              {issueMcpMut.isPending ? '签发中…' : '签发并显示'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* ─── MCP reveal modal(明文 token 仅此一次) ─── */}
      <Dialog
        open={mcpRevealToken != null}
        onOpenChange={(v) => !v && setMcpRevealToken(null)}
      >
        <DialogContent className="max-w-[520px]">
          <DialogHeader>
            <DialogTitle>⚠ MCP 令牌已签发</DialogTitle>
            <DialogDescription>
              请立即复制保存,关闭后将无法再次查看
            </DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-3">
            <div className="rounded-md border border-accent bg-accent-soft p-3.5 text-caption-sm leading-relaxed text-text-primary">
              明文 token 只在签发时显示这一次,关闭后无法再次查看。
            </div>
            <div className="rounded-md border border-border-soft bg-surface-card-2 p-3.5">
              <div className="kq-label">访问令牌</div>
              <div className="kq-mono-row mt-1 break-all text-sm font-bold text-accent">
                {mcpRevealToken}
              </div>
            </div>
            <Button
              variant="outline"
              onClick={() => mcpRevealToken && handleCopyToken(mcpRevealToken)}
            >
              <Copy className="size-3.5" aria-hidden />
              复制令牌
            </Button>
          </div>
          <DialogFooter>
            <Button onClick={() => setMcpRevealToken(null)}>我已保存</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* ─── 4 破坏性 ConfirmDialog(原型全 toast 无确认,移植必补) ─── */}
      <ConfirmDialog
        open={deleteLlmTarget != null}
        onOpenChange={(v) => !v && setDeleteLlmTarget(null)}
        title="确认删除 LLM Key"
        description={`删除 ${deleteLlmTarget?.label ?? ''},该操作不可逆。使用该密钥的 AI 对话将失败。`}
        confirmLabel="删除"
        destructive
        loading={deleteLlmMut.isPending}
        onConfirm={handleDeleteLlm}
      />
      <ConfirmDialog
        open={revokeMcpTarget != null}
        onOpenChange={(v) => !v && setRevokeMcpTarget(null)}
        title="确认吊销 MCP 令牌"
        description={`吊销 ${revokeMcpTarget?.name ?? ''},使用该令牌的 AI 助手将立即失去访问权限,不可恢复。`}
        confirmLabel="吊销"
        destructive
        loading={revokeMcpMut.isPending}
        onConfirm={handleRevokeMcp}
      />

      {/* ─── 交易账户:AddAccountDialog + 删除/重置 ConfirmDialog ─── */}
      <AddAccountDialog open={showAddAcc} onOpenChange={setShowAddAcc} createAcc={createAccMut} />

      <ConfirmDialog
        open={deleteAccTarget != null}
        onOpenChange={(o) => { if (!o) setDeleteAccTarget(null) }}
        title="确认删除账户"
        description={`删除 ${deleteAccTarget?.label ?? ''}(${deleteAccTarget?.exchange ?? ''})账户,该操作不可逆,持仓与历史仍保留。`}
        confirmLabel="删除"
        destructive
        loading={deleteAccMut.isPending}
        onConfirm={() => {
          if (!deleteAccTarget) return
          deleteAccMut.mutate(deleteAccTarget.id, {
            onSuccess: () => { toast.success('账户已删除'); setDeleteAccTarget(null) },
            onError: () => toast.error('删除失败,请重试'),
          })
        }}
      />

      <ConfirmDialog
        open={resetAccTarget != null}
        onOpenChange={(o) => { if (!o) setResetAccTarget(null) }}
        title="重置模拟盘"
        description="将清空所有订单与持仓,余额恢复为 10 万虚拟资金。仅模拟盘可重置。"
        confirmLabel={resetAccMut.isPending ? '重置中…' : '重置'}
        destructive
        loading={resetAccMut.isPending}
        onConfirm={() => {
          if (!resetAccTarget || resetAccMut.isPending) return
          resetAccMut.mutate(
            { accountId: resetAccTarget.id },
            {
              onSuccess: () => { toast.success('模拟盘已重置', { description: '已清空持仓与订单,余额恢复为 10 万虚拟资金' }); setResetAccTarget(null) },
              onError: () => toast.error('重置失败,请稍后重试'),
            },
          )
        }}
      />
    </div>
  )
}
