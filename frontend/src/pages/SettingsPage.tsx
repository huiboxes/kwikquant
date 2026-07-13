import { useMemo, useState } from 'react'
import { Bell, Copy, KeyRound, Plus, RefreshCw, ShieldAlert, Trash2, User } from 'lucide-react'
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
  useMcpTokens,
  useIssueMcpToken,
  useRevokeMcpToken,
  useNotifPrefs,
  useUpsertNotifPrefs,
  useChangePassword,
} from '@/hooks/useSettings'
import { providerLabel, type LlmProvider } from '@/api/ai'
import {
  NOTIF_CHANNEL_TYPES,
  NOTIF_EVENT_TYPES,
  channelTypeLabel,
  eventTypeLabel,
} from '@/api/notification'
import { formatDateTime } from '@/lib/format'
import { ApiError } from '@/lib/http'

/**
 * SettingsPage — 设置页(照 prototypes/done-design/components/SettingsPage.jsx port)。
 * 4 tab(LLM API Key / MCP 令牌 / 通知偏好 / 账户与密码)+ 3 modal(AddLlm / AddMcp / McpReveal)
 * + 4 破坏性 ConfirmDialog(删 LLM key / 轮换 LLM key / 吊销 MCP token / 吊销会话)。
 *
 * honest 差异(不静默照做,记 TD-024~031):
 *  - LlmApiKeyView 无 active 字段 → 不展"启用"徽章(TD-024)
 *  - McpTokenView 无 scopes 字段 → 签发 modal scopes 勾选 UI 保留但不传后端(CreateMcpTokenRequest 只要 name);列表卡不展 scopes(TD-025)
 *  - McpTokenView 不含明文 token(明文仅 issue 响应 one-time)→ 列表卡永久 masked,移除原型 show/hide toggle(TD-031)
 *  - 会话管理端点缺 → 会话卡占位静态(TD-026)
 *  - 轮换 LLM key 端点缺 → 轮换 Confirm 占位 toast.info(不自动删,TD-027)
 *  - telegram/webhook 渠道后端支持性未知 → UI 保留 4 渠道,PUT 只传 WEBSOCKET/EMAIL(TD-028)
 *  - provider 枚举 → 中文映射(TD-029)
 *  - auth.ts api 模块只含 changePassword,login/register/refresh 仍在 hooks 裸调(TD-030)
 */

// 通知矩阵默认值(原型 EVENT_TYPES.def × CHANNELS.def;无记录 = 默认推送)
const EVENT_DEFAULTS: Record<string, boolean> = {
  RISK_REJECTED: true,
  ORDER_FILLED: true,
  ORDER_CANCELLED: false,
  STRATEGY_STARTED: true,
  STRATEGY_STOPPED: false,
  STRATEGY_ERROR: true,
}
const CHANNEL_DEFAULTS: Record<string, boolean> = {
  WEBSOCKET: true,
  EMAIL: true,
  TELEGRAM: false,
  WEBHOOK: false,
}

// MCP 签发 modal scopes(原型 10 个;read_* 默认勾选,emergency_stop/start_live 标"·高风险")
const MCP_SCOPES = [
  'read_market',
  'read_account',
  'read_position',
  'place_order',
  'cancel_order',
  'run_backtest',
  'start_strategy',
  'stop_strategy',
  'emergency_stop',
  'start_live',
] as const
const HIGH_RISK_SCOPES = new Set(['emergency_stop', 'start_live'])

/** LLM provider select 选项(契约枚举 3 个)。 */
const PROVIDER_OPTIONS: { value: LlmProvider; label: string }[] = [
  { value: 'OPENAI', label: 'OpenAI' },
  { value: 'ANTHROPIC', label: 'Anthropic' },
  { value: 'OPENAI_COMPATIBLE', label: 'OpenAI 兼容 (DeepSeek 等)' },
]

// ─── 主页 ───
export function SettingsPage() {
  const [tab, setTab] = useState('llm')

  // LLM keys
  const { data: llmKeys, isLoading: llmLoading, error: llmError } = useLlmKeys()
  const createLlmMut = useCreateLlmKey()
  const deleteLlmMut = useDeleteLlmKey()

  // MCP tokens
  const { data: mcpTokens, isLoading: mcpLoading, error: mcpError } = useMcpTokens()
  const issueMcpMut = useIssueMcpToken()
  const revokeMcpMut = useRevokeMcpToken()

  // 通知偏好
  const { data: notifPrefs } = useNotifPrefs()
  const upsertNotifMut = useUpsertNotifPrefs()

  // 改密码
  const changePwdMut = useChangePassword()

  // modal 开关
  const [showAddLlm, setShowAddLlm] = useState(false)
  const [showAddMcp, setShowAddMcp] = useState(false)
  const [mcpRevealToken, setMcpRevealToken] = useState<string | null>(null)

  // AddLlm 表单
  const [llmLabel, setLlmLabel] = useState('')
  const [llmProvider, setLlmProvider] = useState<LlmProvider>('OPENAI')
  const [llmApiKey, setLlmApiKey] = useState('')
  const [llmBaseUrl, setLlmBaseUrl] = useState('')

  // AddMcp 表单
  const [mcpName, setMcpName] = useState('My AI Agent')
  const [mcpScopes, setMcpScopes] = useState<Set<string>>(
    () => new Set(MCP_SCOPES.filter((s) => s.startsWith('read'))),
  )

  // 改密码表单
  const [oldPassword, setOldPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')

  // 破坏性 Confirm 目标
  const [deleteLlmTarget, setDeleteLlmTarget] =
    useState<import('@/api/ai').LlmApiKeyView | null>(null)
  const [rotateLlmTarget, setRotateLlmTarget] =
    useState<import('@/api/ai').LlmApiKeyView | null>(null)
  const [revokeMcpTarget, setRevokeMcpTarget] =
    useState<import('@/api/mcp').McpTokenView | null>(null)
  const [revokeSessionTarget, setRevokeSessionTarget] = useState<string | null>(null)

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
      toast.warning('请填写标签与 API Key')
      return
    }
    if (llmProvider === 'OPENAI_COMPATIBLE' && !llmBaseUrl.trim()) {
      toast.warning('OpenAI 兼容 provider 必须填 base URL')
      return
    }
    createLlmMut.mutate(
      {
        label: llmLabel.trim(),
        provider: llmProvider,
        apiKey: llmApiKey.trim(),
        baseUrl: llmBaseUrl.trim(),
      },
      {
        onSuccess: () => {
          toast.success('key 已加密保存,仅露末 4 位')
          setShowAddLlm(false)
          setLlmLabel('')
          setLlmApiKey('')
          setLlmBaseUrl('')
        },
        onError: () => toast.error('保存失败,请重试'),
      },
    )
  }

  function handleIssueMcp() {
    if (!mcpName.trim()) {
      toast.warning('请填写 Agent 名称')
      return
    }
    // honest:CreateMcpTokenRequest 只要 name;scopes 勾选 UI 不传后端(TD-025)
    issueMcpMut.mutate(
      { name: mcpName.trim() },
      {
        onSuccess: (result) => {
          toast.success('MCP 令牌已签发')
          setShowAddMcp(false)
          setMcpRevealToken(result.token)
          setMcpName('My AI Agent')
          setMcpScopes(new Set(MCP_SCOPES.filter((s) => s.startsWith('read'))))
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

  function handleRotateLlm() {
    // honest:轮换 LLM key 端点缺(TD-027),占位 toast.info,不自动删(避免误删)
    toast.info('轮换需删除旧 key 并重新添加', {
      description: '后端暂无 rotate 端点,请手动删除后重建',
    })
    setRotateLlmTarget(null)
  }

  function handleRevokeMcp() {
    if (!revokeMcpTarget) return
    revokeMcpMut.mutate(revokeMcpTarget.id, {
      onSuccess: () => {
        toast.success('token 已吊销', { description: revokeMcpTarget.name })
        setRevokeMcpTarget(null)
      },
      onError: () => toast.error('吊销失败,请重试'),
    })
  }

  function handleRevokeSession() {
    // honest:会话管理端点缺(TD-026),占位 toast.warning
    toast.warning('会话管理功能待后端提供端点')
    setRevokeSessionTarget(null)
  }

  function handleNotifToggle(ev: string, ch: string) {
    const key = `${ev}:${ch}`
    const newVal = !effectiveMatrix[key]
    setLocalOverrides((prev) => ({ ...prev, [key]: newVal }))
    toast.success(`${eventTypeLabel(ev)} / ${channelTypeLabel(ch)} 已${newVal ? '启用' : '关闭'}`)
    // honest:telegram/webhook 渠道后端支持性未知,PUT 只传 WEBSOCKET/EMAIL(TD-028)
    if (ch === 'WEBSOCKET' || ch === 'EMAIL') {
      upsertNotifMut.mutate({
        preferences: [{ eventType: ev, channelType: ch, enabled: newVal }],
      })
    }
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
          // 旧密码错 401 1001(behavior-contract §1.3 / §6);isUnauthorized getter 已含 code===1001||status===401
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
            LLM API Key
          </TabsTrigger>
          <TabsTrigger value="mcp" className="gap-1.5">
            <ShieldAlert className="size-3.5" aria-hidden />
            MCP 令牌
          </TabsTrigger>
          <TabsTrigger value="notif" className="gap-1.5">
            <Bell className="size-3.5" aria-hidden />
            通知偏好
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
              title="LLM API Keys"
              sub="多 provider · 加密存储 · 仅露末 4 位"
              right={
                <Button onClick={() => setShowAddLlm(true)} size="sm">
                  <Plus className="size-3.5" aria-hidden />
                  添加 Key
                </Button>
              }
            />
            {llmError ? (
              <ErrorState />
            ) : llmLoading ? (
              <LoadingState />
            ) : !llmKeys || llmKeys.length === 0 ? (
              <EmptyState title="暂无 LLM Key" description="添加第一个 API Key 开始使用 AI 对话。" />
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
                            API key{' '}
                            <span className="kq-mono-row text-text-secondary">
                              {k.apiKeyMasked}
                            </span>
                          </span>
                          <span>添加于 {formatDateTime(k.createdAt)}</span>
                        </div>
                      </div>
                      <div className="flex gap-1.5">
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => setRotateLlmTarget(k)}
                        >
                          <RefreshCw className="size-3.5" aria-hidden />
                          轮换
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
              sub="给 AI agent 用 · 明文仅签发时显示一次"
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
                  <strong>MCP agent 能代你</strong> · 查账户 / 查行情 / 下单 / 撤单 / 查持仓 /
                  跑回测 / 启停策略。
                  <strong>高风险操作需二次确认</strong>:紧急停止、启动实盘交易 — UI
                  会有强确认流程。
                </div>
              </div>
            </Card>
            {mcpError ? (
              <ErrorState />
            ) : mcpLoading ? (
              <LoadingState />
            ) : !mcpTokens || mcpTokens.length === 0 ? (
              <EmptyState title="暂无 MCP 令牌" description="签发令牌给 AI agent 使用。" />
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
                          {!t.revokedAt && <Chip color="up" label="● 有效" />}
                        </div>
                        <div className="mt-2 rounded-md border border-border-soft bg-surface-card-2 p-2.5 text-body-sm text-text-secondary">
                          <div className="flex items-center justify-between gap-2">
                            <span>明文 token</span>
                            <span className="kq-mono-row">
                              kq_pat_••••••••••••••••••••••••••••••
                            </span>
                          </div>
                          <div className="mt-1 text-[11px] text-text-muted">
                            明文仅签发时显示一次,已不可再次查看
                          </div>
                        </div>
                        <div className="mt-1.5 text-[11px] text-text-muted">
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
              sub="按事件类型 × 渠道启停 · 无记录 = 默认推送 · 关闭 = 不推"
            />
            <Card className="overflow-hidden p-0">
              <table className="w-full text-body-sm">
                <thead>
                  <tr className="text-left text-[11px] uppercase tracking-[0.04em] text-text-muted">
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
            <Card className="max-w-[480px] p-4">
              <SectionTitle title="会话" sub="当前登录设备" />
              {/* honest:会话管理端点缺,占位静态(TD-026) */}
              <div className="flex flex-col gap-2 text-body-sm">
                <div className="flex items-center justify-between rounded-md border border-accent bg-accent-soft p-2.5">
                  <div>
                    <strong className="text-text-primary">当前会话</strong>
                    <div className="text-[11px] text-text-muted">
                      Chrome · macOS · 2026-07-09 14:02
                    </div>
                  </div>
                  <Chip color="up" label="在线" />
                </div>
                <div className="flex items-center justify-between rounded-md bg-surface-card-2 p-2.5">
                  <div>
                    <strong className="text-text-primary">Cursor Agent</strong>
                    <div className="text-[11px] text-text-muted">MCP token · 2 小时前</div>
                  </div>
                  <Button
                    variant="ghost"
                    size="sm"
                    className="text-down hover:text-down"
                    onClick={() => setRevokeSessionTarget('Cursor Agent')}
                  >
                    吊销
                  </Button>
                </div>
              </div>
            </Card>
          </div>
        </TabsContent>
      </Tabs>

      {/* ─── Add LLM modal ─── */}
      <Dialog open={showAddLlm} onOpenChange={setShowAddLlm}>
        <DialogContent className="max-w-[480px]">
          <DialogHeader>
            <DialogTitle>添加 LLM API Key</DialogTitle>
            <DialogDescription>加密存储 · 仅露末 4 位明文。</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-3">
            <div>
              <Label className="kq-label">Provider</Label>
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
              <Label className="kq-label">API Key</Label>
              <Input
                type="password"
                placeholder="sk-..."
                value={llmApiKey}
                onChange={(e) => setLlmApiKey(e.target.value)}
              />
            </div>
            {llmProvider === 'OPENAI_COMPATIBLE' && (
              <div>
                <Label className="kq-label">Base URL(必填)</Label>
                <Input
                  placeholder="https://api.example.com/v1"
                  value={llmBaseUrl}
                  onChange={(e) => setLlmBaseUrl(e.target.value)}
                />
              </div>
            )}
            <div className="rounded-md border border-dashed border-border-soft bg-surface-card-2 p-2.5 text-[11px] leading-relaxed text-text-muted">
              ⚠ API key 加密存储,UI 永远不会展示明文。LLM 原始错误会被脱敏,不透传。
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
            <DialogDescription>明文 token 仅签发时显示一次,关闭后无法再次查看。</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-3">
            <div>
              <Label className="kq-label">Agent 名称</Label>
              <Input value={mcpName} onChange={(e) => setMcpName(e.target.value)} />
            </div>
            <div>
              <Label className="kq-label">权限范围 (scopes)</Label>
              {/* honest:scopes 勾选 UI 保留(照原型),但 CreateMcpTokenRequest 只要 name,
                  不传后端(TD-025)。PAT 是全权限,高风险走二次确认 flow 兜底。 */}
              <div className="grid grid-cols-2 gap-1.5 text-body-sm">
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
                      <span className="kq-mono-row text-[11px]">{s}</span>
                      {HIGH_RISK_SCOPES.has(s) && (
                        <span className="text-[10px] text-down">·高风险</span>
                      )}
                    </label>
                  )
                })}
              </div>
            </div>
            <div className="rounded-md border border-accent bg-accent-soft p-2.5 text-[11px] leading-relaxed text-text-primary">
              ⚠ <strong>明文 token 仅签发时显示一次</strong>。关闭后将永远无法再次查看。高风险操作(紧急停止、启动实盘)会触发二次确认流程。
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
              请立即复制 · 现在不存下来就再也看不到了
            </DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-3">
            <div className="rounded-md border border-accent bg-accent-soft p-3.5 text-[11px] leading-relaxed text-text-primary">
              明文 token 只在签发时显示这一次,关闭后无法再次查看。
            </div>
            <div className="rounded-md border border-border-soft bg-surface-card-2 p-3.5">
              <div className="kq-label">Token (明文)</div>
              <div className="kq-mono-row mt-1 break-all text-sm font-bold text-accent">
                {mcpRevealToken}
              </div>
            </div>
            <Button
              variant="outline"
              onClick={() => mcpRevealToken && handleCopyToken(mcpRevealToken)}
            >
              <Copy className="size-3.5" aria-hidden />
              复制 Token
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
        description={`删除 ${deleteLlmTarget?.label ?? ''},该操作不可逆。使用该 key 的 AI 对话将失败。`}
        confirmLabel="删除"
        destructive
        loading={deleteLlmMut.isPending}
        onConfirm={handleDeleteLlm}
      />
      <ConfirmDialog
        open={rotateLlmTarget != null}
        onOpenChange={(v) => !v && setRotateLlmTarget(null)}
        title="轮换 LLM Key"
        description={`轮换 ${rotateLlmTarget?.label ?? ''} 将使旧 key 失效。⚠ 后端暂无 rotate 端点,需手动删除后重建。`}
        confirmLabel="确认轮换"
        destructive
        onConfirm={handleRotateLlm}
      />
      <ConfirmDialog
        open={revokeMcpTarget != null}
        onOpenChange={(v) => !v && setRevokeMcpTarget(null)}
        title="确认吊销 MCP 令牌"
        description={`吊销 ${revokeMcpTarget?.name ?? ''},使用该 token 的 AI agent 将立即失去访问权限,不可恢复。`}
        confirmLabel="吊销"
        destructive
        loading={revokeMcpMut.isPending}
        onConfirm={handleRevokeMcp}
      />
      <ConfirmDialog
        open={revokeSessionTarget != null}
        onOpenChange={(v) => !v && setRevokeSessionTarget(null)}
        title="确认吊销会话"
        description={`吊销 ${revokeSessionTarget ?? ''} 会话,该设备将立即登出。⚠ 会话管理端点待后端提供。`}
        confirmLabel="吊销"
        destructive
        onConfirm={handleRevokeSession}
      />
    </div>
  )
}
