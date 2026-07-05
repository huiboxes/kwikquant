import Editor, { type OnMount } from '@monaco-editor/react'
import { LoadingState } from './feedback/LoadingState'

/**
 * MonacoEditor — 代码编辑器封装(spec §5 step 13)。
 *
 * @monaco-editor/react 通过 loader 从 CDN 动态加载 monaco(运行时,build 不含 monaco 本体,
 * 保 dist 体积小 — step 18 verify)。编辑器加载时显示 LoadingState。
 *
 * 主题:vs-dark 对齐 DESIGN.md 暗主。语言:python(kwikquant_worker 用 Python)。
 * 懒加载:工作区路由已是 lazy code-split,Editor wrapper 本身轻,无需额外 React.lazy。
 */
export interface MonacoEditorProps {
  value: string
  onChange?: (value: string) => void
  language?: string
  height?: number | string
  readOnly?: boolean
}

export function MonacoEditor({
  value,
  onChange,
  language = 'python',
  height = 480,
  readOnly = false,
}: MonacoEditorProps) {
  const handleMount: OnMount = (editor) => {
    // 编辑器挂载后无额外配置(语法高亮由 monaco 内置 python grammar)
    void editor
  }

  return (
    <Editor
      value={value}
      language={language}
      height={height}
      theme="vs-dark"
      onMount={handleMount}
      onChange={(v) => onChange?.(v ?? '')}
      loading={<LoadingState label="加载编辑器…" />}
      options={{
        minimap: { enabled: false },
        fontSize: 13,
        lineNumbers: 'on',
        scrollBeyondLastLine: false,
        wordWrap: 'on',
        tabSize: 4,
        readOnly,
        automaticLayout: true,
      }}
    />
  )
}
