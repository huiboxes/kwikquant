import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

/**
 * ESLint 配置——脚手架阶段只带一条金额红线 + shadcn ui 目录豁免。
 *
 * 刻意不引入自定义 design-tokens 插件（老前端 UI 差的根因之一）。
 * 视觉约束的载体是 frontend/DESIGN.md（工程契约）+ `@google/design.md lint`（Step 9），
 * 不再走 className 级 ESLint。
 */
export default defineConfig([
  globalIgnores(['dist', 'src/types/api-gen.ts']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      globals: globals.browser,
    },
    rules: {
      // 金额红线：金额一律 decimal.js，禁止 JS double 参与金额运算（53 位尾数静默丢精度）。
      // 默认拦截，非金额场景确需时用 eslint-disable-next-line 显式豁免并说明——可审计。
      'no-restricted-syntax': [
        'error',
        {
          selector: "CallExpression[callee.name='parseFloat']",
          message:
            '金额红线：禁止 parseFloat 参与金额运算，金额用 decimal.js（new Decimal(str)）。非金额场景如确需，请 eslint-disable 并注明理由。',
        },
        {
          selector: "CallExpression[callee.name='Number']",
          message:
            '金额红线：禁止 Number() 参与金额运算（JS double 丢精度），金额用 decimal.js。非金额场景如确需，请 eslint-disable 并注明理由。',
        },
      ],
    },
  },
  {
    // shadcn/ui 生成组件：约定同文件导出组件 + variants 常量（如 buttonVariants），
    // 与 react-refresh/only-export-components 冲突。这是 shadcn 官方模式，豁免该规则。
    files: ['src/components/ui/**/*.{ts,tsx}'],
    rules: {
      'react-refresh/only-export-components': 'off',
    },
  },
])
