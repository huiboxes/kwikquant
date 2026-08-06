import { loader } from '@monaco-editor/react'
import * as monaco from 'monaco-editor'
// 路径走 monaco-editor 的 exports 子路径(`./*.js`→`./esm/vs/*.js`),不能带 esm/vs 前缀否则双映射解析失败。
import editorWorker from 'monaco-editor/editor/editor.worker.js?worker'

// 绕过 @monaco-editor/react 默认的 jsdelivr CDN 加载,改用本地 bundle 的 monaco-editor 实例。
// 策略代码只写 Python(basic language),只需 editorWorker,无需 ts/json/css/html worker。
// 放在 StrategyPage(懒加载)链路里,monaco-editor 只进策略页 chunk,不污染首屏 bundle。
;(self as unknown as { MonacoEnvironment: { getWorker: () => Worker } }).MonacoEnvironment = {
  getWorker: () => new editorWorker(),
}

loader.config({ monaco })
