import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  fetchLlmKeys,
  createLlmKey,
  deleteLlmKey,
} from '@/api/ai'
import { fetchMcpTokens, issueMcpToken, revokeMcpToken } from '@/api/mcp'
import { fetchNotifPrefs, upsertNotifPrefs } from '@/api/notification'
import { changePassword } from '@/api/auth'
import { aiKeys, mcpKeys, notifKeys } from '@/api/_queryKeys'
import type {
  LlmApiKeyView,
  CreateLlmKeyRequest,
} from '@/api/ai'
import type {
  McpTokenView,
  McpTokenIssueResult,
  CreateMcpTokenRequest,
} from '@/api/mcp'
import type {
  NotificationPreferenceDto,
  NotificationPreferenceRequest,
} from '@/api/notification'
import type { ChangePasswordRequest } from '@/api/auth'

/**
 * useSettings — SettingsPage 4 tab 聚合 hook(LLM key + MCP token + 通知偏好 + 改密码)。
 *
 * 设计:服务端数据走 react-query,客户端态(page 内 tab/modal 开关)用 useState。
 *  - list 走 useQuery,mutation 成功后 invalidate 对应 list key 自动 refetch。
 *  - MCP 签发明文 token 在 mutation data(McpTokenIssueResult.token),page 层转 McpReveal
 *    modal 展示(明文仅此一次,不入 cache,modal 关闭即丢弃)。
 *  - 通知偏好矩阵:GET 返已显式设置的偏好项,page 层用 default matrix(def)填充未返回组合。
 *  - 轮换 LLM key 无后端端点(TD-027),走 ConfirmDialog 占位 toast,不经 mutation。
 *  - 吊销会话无后端端点(TD-026),走 ConfirmDialog 占位 toast,不经 mutation。
 */

/** LLM key 列表(GET /ai/keys)。llm tab 数据源。 */
export function useLlmKeys() {
  return useQuery({ queryKey: aiKeys.keys(), queryFn: fetchLlmKeys })
}

/** 创建 LLM key(mutation;成功 invalidate key 列表)。AddLlm modal 保存用。 */
export function useCreateLlmKey() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (req: CreateLlmKeyRequest) => createLlmKey(req),
    onSuccess: () => qc.invalidateQueries({ queryKey: aiKeys.keys() }),
  })
}

/** 删 LLM key(mutation;成功 invalidate key 列表)。删 key ConfirmDialog destructive 用。 */
export function useDeleteLlmKey() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => deleteLlmKey(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: aiKeys.keys() }),
  })
}

/** MCP token 列表(GET /mcp/tokens)。mcp tab 数据源。 */
export function useMcpTokens() {
  return useQuery({ queryKey: mcpKeys.tokens(), queryFn: fetchMcpTokens })
}

/** 签发 MCP token(mutation;返明文 token 仅此一次,page 层转 McpReveal modal)。 */
export function useIssueMcpToken() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (req: CreateMcpTokenRequest) => issueMcpToken(req),
    onSuccess: () => qc.invalidateQueries({ queryKey: mcpKeys.tokens() }),
  })
}

/** 吊销 MCP token(mutation;成功 invalidate token 列表)。吊销 ConfirmDialog destructive 用。 */
export function useRevokeMcpToken() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => revokeMcpToken(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: mcpKeys.tokens() }),
  })
}

/** 通知偏好列表(GET)。notif tab 矩阵数据源(已显式设置项;未返回组合走默认)。 */
export function useNotifPrefs() {
  return useQuery({ queryKey: notifKeys.preferences(), queryFn: fetchNotifPrefs })
}

/** 批量更新通知偏好(mutation PUT 幂等 upsert;成功 invalidate)。checkbox onChange 用。 */
export function useUpsertNotifPrefs() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (req: NotificationPreferenceRequest) => upsertNotifPrefs(req),
    onSuccess: () => qc.invalidateQueries({ queryKey: notifKeys.preferences() }),
  })
}

/** 修改密码(mutation POST;旧密码错 401 1001,page 层 onError toast.error)。 */
export function useChangePassword() {
  return useMutation({
    mutationFn: (req: ChangePasswordRequest) => changePassword(req),
  })
}

export type {
  LlmApiKeyView,
  CreateLlmKeyRequest,
  McpTokenView,
  McpTokenIssueResult,
  CreateMcpTokenRequest,
  NotificationPreferenceDto,
  NotificationPreferenceRequest,
  ChangePasswordRequest,
}
