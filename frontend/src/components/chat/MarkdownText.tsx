import { memo, useState, type ReactElement, type ReactNode } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { Check, Copy } from 'lucide-react'
import { cn } from '@/lib/utils'

/**
 * MarkdownText — 自建 markdown 渲染(弃 assistant-ui MarkdownTextPrimitive,后者从 runtime
 * 上下文取 text 零 props 不可复用)。react-markdown v10 + remark-gfm,text prop 驱动。
 *
 * 代码块:pre 拦截(block code 走 CodeBlock,带语言标签 + 复制按钮);code 组件只处理 inline。
 * 不引 shiki —— 流式期语法高亮开销大(每 chunk 全量 re-parse 高亮),代码块纯 mono 配色,
 * 闭合后也不二次高亮(策略代码片段通常 <50 行,无需高亮足够可读)。
 *
 * 流式期 react-markdown 对未闭合 fence 当纯文本渲染(前段成 inline code,后段闭合后成 block),
 * Cursor/Claude.ai 同款行为,可接受。
 */
interface MarkdownTextProps {
  text: string
  className?: string
}

interface CodeBlockProps {
  lang?: string
  text: string
}

function CodeBlock({ lang, text }: CodeBlockProps) {
  const [copied, setCopied] = useState(false)
  const onCopy = async () => {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1500)
    } catch {
      // clipboard 不可用(非 https / 无权限),静默 — 不阻断渲染
    }
  }
  return (
    <div className="my-sm overflow-hidden rounded-md border border-border-soft bg-surface-card-2">
      <div className="flex items-center justify-between border-b border-border-soft px-sm py-xxs">
        <span className="text-label-caps text-text-muted">{lang ?? 'code'}</span>
        <button
          type="button"
          onClick={onCopy}
          className="flex items-center gap-xxs rounded text-caption text-text-secondary transition-colors hover:text-text-primary"
          aria-label="复制代码"
        >
          {copied ? <Check className="size-3" aria-hidden /> : <Copy className="size-3" aria-hidden />}
          {copied ? '已复制' : '复制'}
        </button>
      </div>
      <pre className="overflow-x-auto p-sm font-mono text-caption leading-relaxed text-text-primary">
        <code>{text}</code>
      </pre>
    </div>
  )
}

/** 从 react-markdown pre 的 children(单个 <code> element)提取 lang + text。 */
function extractCode(children: ReactNode): { lang?: string; text: string } {
  const codeEl = (Array.isArray(children) ? children[0] : children) as
    | ReactElement<{ className?: string; children?: ReactNode }>
    | undefined
  const props = codeEl?.props ?? {}
  const cls = props.className ?? ''
  const lang = /language-(\w+)/.exec(cls)?.[1]
  const raw = props.children
  const text =
    typeof raw === 'string'
      ? raw.replace(/\n$/, '')
      : Array.isArray(raw)
        ? raw.join('')
        : ''
  return { lang, text }
}

export const MarkdownText = memo(function MarkdownText({ text, className }: MarkdownTextProps) {
  return (
    <div className={cn('markdown-text text-body-sm leading-relaxed text-text-primary', className)}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          pre: ({ children }) => {
            const { lang, text: codeText } = extractCode(children)
            return <CodeBlock lang={lang} text={codeText} />
          },
          code: ({ className: cls, children }) => (
            <code className={cn('font-mono', cls)}>{children}</code>
          ),
          a: ({ children, ...rest }) => (
            <a
              className="text-accent underline underline-offset-2"
              {...rest}
              target="_blank"
              rel="noreferrer"
            >
              {children}
            </a>
          ),
          ul: ({ children }) => <ul className="my-xs list-disc pl-md">{children}</ul>,
          ol: ({ children }) => <ol className="my-xs list-decimal pl-md">{children}</ol>,
          li: ({ children }) => <li className="my-xxs">{children}</li>,
          h1: ({ children }) => (
            <h1 className="my-sm text-sm font-semibold">{children}</h1>
          ),
          h2: ({ children }) => (
            <h2 className="my-sm text-sm font-semibold">{children}</h2>
          ),
          h3: ({ children }) => (
            <h3 className="my-xs text-sm font-semibold">{children}</h3>
          ),
          p: ({ children }) => <p className="my-xs whitespace-pre-wrap">{children}</p>,
          table: ({ children }) => (
            <div className="my-sm overflow-x-auto">
              <table className="w-full border-collapse text-caption">{children}</table>
            </div>
          ),
          th: ({ children }) => (
            <th className="border border-border-soft px-sm py-xxs text-left font-semibold">
              {children}
            </th>
          ),
          td: ({ children }) => (
            <td className="border border-border-soft px-sm py-xxs">{children}</td>
          ),
          blockquote: ({ children }) => (
            <blockquote className="my-xs border-l-2 border-border-soft pl-sm text-text-secondary">
              {children}
            </blockquote>
          ),
        }}
      >
        {text}
      </ReactMarkdown>
    </div>
  )
})
