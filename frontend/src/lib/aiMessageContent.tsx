import type { ReactNode } from 'react'

/**
 * renderMessageContent — AI 气泡 content 高亮渲染(spec §4.5.3)。
 *
 * 1. 反引号 `code` → inline <code> 高亮(背景 + 等宽字体)
 * 2. "X% → Y%" 数值变化 → 前红(text-down)后绿(text-up),红色恶化/绿色改善
 *
 * content 是 AI 回复(不可信输入),React 默认转义文本节点防 XSS,不用 dangerouslySetInnerHTML。
 * 金额/数值用 font-mono-num(DESIGN.md §Do's and Don'ts: 数字 mono + tnum)。
 */

const NUM_CHANGE_RE = /(-?\d+(?:\.\d+)?%)\s*→\s*(-?\d+(?:\.\d+)?%)/g

function renderTextSegment(text: string, keyBase: string): ReactNode[] {
  const parts: ReactNode[] = []
  let lastIdx = 0
  let m: RegExpExecArray | null
  NUM_CHANGE_RE.lastIndex = 0
  let i = 0
  while ((m = NUM_CHANGE_RE.exec(text)) !== null) {
    if (m.index > lastIdx) parts.push(text.slice(lastIdx, m.index))
    parts.push(
      <span key={`${keyBase}-r${i}`} className="font-mono-num text-down">
        {m[1]}
      </span>,
    )
    parts.push(' → ')
    parts.push(
      <span key={`${keyBase}-g${i}`} className="font-mono-num text-up">
        {m[2]}
      </span>,
    )
    lastIdx = m.index + m[0].length
    i++
  }
  if (lastIdx < text.length) parts.push(text.slice(lastIdx))
  return parts
}

export function renderMessageContent(content: string): ReactNode[] {
  // 按反引号 split,奇数段(idx % 2 === 1)是 inline code
  const segments = content.split('`')
  const nodes: ReactNode[] = []
  segments.forEach((seg, idx) => {
    if (idx % 2 === 1) {
      nodes.push(
        <code
          key={`code-${idx}`}
          className="rounded-sm bg-surface-hover px-xs font-mono text-body-sm text-text-primary"
        >
          {seg}
        </code>,
      )
    } else if (seg) {
      renderTextSegment(seg, `text-${idx}`).forEach((n) => nodes.push(n))
    }
  })
  return nodes
}
