#!/usr/bin/env node
// 从 OpenAPI /v3/api-docs 生成 docs/api-reference.md(防手写漂移)。
// 改后端 controller 注解后重跑:node frontend/scripts/gen-api-reference.mjs
// env KWIKQUANT_API_DOCS 覆盖默认 http://localhost:8080/v3/api-docs
import { writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const root = resolve(__dirname, '../..') // frontend/scripts/ → 仓库根
const DOCS_URL = process.env.KWIKQUANT_API_DOCS || 'http://localhost:8080/v3/api-docs'
const OUT = resolve(root, 'docs/api-reference.md')

const spec = await fetch(DOCS_URL).then((r) => r.json())
const paths = spec.paths || {}
const methodOrder = ['get', 'post', 'put', 'patch', 'delete']

// 按 /api/v1/<group> 分组
const groups = {}
for (const [path, item] of Object.entries(paths)) {
  const seg = path.split('/').slice(0, 4).join('/') // /api/v1/accounts
  const key = seg.replace('/api/v1/', '') || 'root'
  ;(groups[key] ||= []).push({ path, item })
}

const clean = (s) => (s || '').replace(/\|/g, '\\|').replace(/\n/g, ' ').trim()
const schemaName = (sch) => {
  if (!sch) return '-'
  if (sch.$ref) return sch.$ref.split('/').pop()
  if (sch.type === 'array') return `array<${schemaName(sch.items)}>`
  if (sch.allOf?.[0]?.$ref) return sch.allOf[0].$ref.split('/').pop()
  return sch.type || '-'
}

let md = `# REST API Reference\n\n`
md += `> 自动从 OpenAPI \`/v3/api-docs\` 生成,**勿手写**。改后端 controller 注解后重跑 \`node frontend/scripts/gen-api-reference.mjs\`。\n`
md += `> 当前 ${Object.keys(paths).length} 个端点。OpenAPI 原文:运行时 \`http://localhost:8080/v3/api-docs\`。\n\n`
md += `所有端点返 \`ApiResponse<T>\` = \`{code, message, data}\`,成功 \`code=0\`;错误码见 [behavior-contract](behavior-contract.md)。\n\n`
md += `## 目录\n\n`
for (const g of Object.keys(groups).sort()) md += `- [${g}](#${g})\n`
md += `\n`

for (const g of Object.keys(groups).sort()) {
  md += `## ${g}\n\n`
  for (const { path, item } of groups[g]) {
    for (const m of methodOrder) {
      if (!item[m]) continue
      const op = item[m]
      md += `### \`${m.toUpperCase()} ${path}\`\n\n`
      if (op.summary) md += `**${op.summary}**\n\n`
      if (op.description) md += `${op.description}\n\n`
      const params = op.parameters || []
      if (params.length) {
        md += `| 参数 | 位置 | 必填 | 类型 | 说明 |\n|---|---|---|---|---|\n`
        for (const p of params) {
          md += `| \`${p.name}\` | ${p.in} | ${p.required ? '是' : '否'} | ${p.schema?.type || schemaName(p.schema)} | ${clean(p.description).slice(0, 90)} |\n`
        }
        md += `\n`
      }
      const rb = op.requestBody
      if (rb?.content?.['application/json']?.schema) {
        md += `请求体: \`${schemaName(rb.content['application/json'].schema)}\`\n\n`
      }
      const codes = Object.keys(op.responses || {})
      if (codes.length) {
        md += `响应:`
        for (const c of codes) md += ` \`${c}\` ${clean(op.responses[c].description).slice(0, 60)};`
        md += `\n\n`
      }
    }
  }
}

writeFileSync(OUT, md)
console.log(`✓ ${OUT} (${Object.keys(paths).length} paths, ${Object.keys(groups).length} groups)`)
