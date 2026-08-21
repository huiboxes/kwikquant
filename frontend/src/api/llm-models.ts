import type { LlmProvider } from '@/api/ai'

/**
 * 按 provider 分组的常见模型(2026-07)；用户可在 settings 自定义加/删，预置库仅快捷选项。
 *
 * 注意：模型名需对 provider 官方文档校准(发布前对一次)；预置库错误模型名 = 用户测试连通必失败。
 * adapter.defaultModel() 后端兜底值以代码为准(非本预置库)，预置库与 adapter 默认是两回事。
 */
export const LLM_MODELS: Record<LlmProvider, string[]> = {
  OPENAI: ['gpt-5.6', 'gpt-5.2', 'gpt-5.1', 'gpt-5', 'gpt-5-mini', 'gpt-5-codex', 'o3'],
  ANTHROPIC: ['claude-opus-4-8', 'claude-sonnet-4-6', 'claude-haiku-4-5', 'claude-fable-5'],
  OPENAI_COMPATIBLE: ['deepseek-v4', 'deepseek-r1', 'deepseek-v3.2', 'glm-5.1', 'glm-5', 'qwen-max'],
}

/** 从预置库按 provider 取候选(combobox 用)。 */
export function candidateModels(provider: LlmProvider): string[] {
  return LLM_MODELS[provider] ?? []
}
