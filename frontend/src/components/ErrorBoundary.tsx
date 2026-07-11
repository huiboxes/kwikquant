import { Component, type ErrorInfo, type ReactNode } from 'react'
import { ErrorState } from './ErrorState'

/**
 * ErrorBoundary — 渲染异常全局兜底(React class error boundary)。
 *
 * 工程级前端必须有的兜底:任何子树渲染 throw(chart 对空 data 崩 / undefined 访问 / 组件 bug)
 * 被捕获后展示 ErrorState 而非白屏报错。挂在路由壳(routes.tsx 的 suspense helper 外层),
 * 每 lazy page 各自一个边界,一页崩不影响侧栏/TopBar/其他页。
 *
 * 局限(留账):纯渲染异常 reset 后若 throw 源仍在会再次崩;react-query error 态需配合
 * QueryErrorResetBoundary 清 query 缓存才能完美 reset。当前 reset = 清 error 重挂载子树,
 * 配合各组件自己的空态防御(如 chart 空数据早返回)足够兜白屏。
 */
interface Props {
  children: ReactNode
  /** 自定义错误描述,不传则用 error.message */
  message?: string
}
interface State {
  error: Error | null
}

export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    // 开发期日志(生产可接 sentry/监控)
    console.error('[ErrorBoundary] 渲染异常:', error, info)
  }

  reset = (): void => {
    this.setState({ error: null })
  }

  render() {
    if (this.state.error) {
      return (
        <ErrorState
          title="页面渲染出错"
          message={this.props.message ?? this.state.error.message}
          onRetry={this.reset}
          retryLabel="重试"
        />
      )
    }
    return this.props.children
  }
}
