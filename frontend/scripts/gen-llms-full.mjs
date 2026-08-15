#!/usr/bin/env node
// 合并关键接入文档 → docs/llms-full.txt(Anthropic llms.txt proposal 的全量单页)。
// AI agent 一次读完能用,不必逐页爬取。改文档后重跑:node frontend/scripts/gen-llms-full.mjs
import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const root = resolve(__dirname, '../..') // frontend/scripts/ → 仓库根
const files = [
  'docs/quickstart.md',
  'docs/cookbook.md',
  'docs/cli-reference.md',
  'docs/mcp-setup.md',
  'docs/llm-integration.md',
  'docs/behavior-contract.md',
  'docs/ws-contract.md',
  'docs/api-reference.md',
  'skills/README.md',
  'skills/install.md',
]

let out = `# KwikQuant — LLM Full Context\n\n`
out += `> 全量单页 markdown(Anthropic llms.txt proposal 的 llms-full.txt)。合并所有接入文档,`
out += `AI agent 一次读完能用,不必逐页爬取。生成:node frontend/scripts/gen-llms-full.mjs。\n`
out += `> 本地起步 localhost:8080;公网分发后替换为 https://kwikquant.dev 前缀。\n`
out += `> 大纲索引见 [llms.txt](llms.txt);OpenAPI 3 规范运行时 http://localhost:8080/v3/api-docs。\n`

for (const f of files) {
  const content = readFileSync(resolve(root, f), 'utf8')
  out += `\n\n---\n\n# Source: ${f}\n\n${content}\n`
}

const OUT = resolve(root, 'docs/llms-full.txt')
writeFileSync(OUT, out)
console.log(`✓ ${OUT} (${out.length} chars, ${files.length} files merged)`)
