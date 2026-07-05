# AGENTS.md

Root instructions for AI coding agents (Claude Code / Codex / Gemini / etc.) working on the KwikQuant monorepo. This file is the shared entry point — Claude Code loads [`CLAUDE.md`](CLAUDE.md) additionally.

## 主要索引

| 文档 | 用途 |
|---|---|
| [`CLAUDE.md`](CLAUDE.md) | 通用项目上下文（架构、模块、命令、约束、Frontend Design Contract 段） |
| [`README.md`](README.md) | 二开上手 — 从零到跑起后端 + 前端 |
| [`docs/README.md`](docs/README.md) | 产品/架构/实施计划权威文档索引 |
| [`frontend/DESIGN.md`](frontend/DESIGN.md) | **前端视觉唯一真相源**（token 权威 + Agent 拦截条款） |
| [`frontend/SCAFFOLD-REF.md`](frontend/SCAFFOLD-REF.md) | 前端脚手架搭建清单（一次性参考） |

## 硬约束速查（违反即拒绝）

**金额**
一律用 `decimal.js`。禁止 `parseFloat` / `Number` 参与金额运算。后端 `BigDecimal` 全局序列化为带引号 `string`，前端 `toDecimal` 接收（见 `frontend/src/lib/money.ts`）。ESLint `no-restricted-syntax` 硬拦。

**前端 UI**
视觉工作必读 [`frontend/DESIGN.md`](frontend/DESIGN.md)。硬编码颜色 / 圆角 / 字号被拦截。冲突走 §Do's and Don'ts §Agent 实现约束三段式（引用条款 + Token 化替代方案 + 反问用户）。CI 跑 `@google/design.md lint`（0 errors）。

**前后端契约**
契约由后端 OpenAPI 生成，路径 `/v3/api-docs`。前端跑 `pnpm gen:api` 得到 `frontend/src/types/api-gen.ts`。**严禁手写重复类型**。CI 跑 `pnpm gen:api:check` 拦漂移。

**后端模块边界**
Spring Modulith `@ApplicationModule` 强边界，`ArchitectureTests` 测试期强制。`domain/` 严禁依赖 Spring。跨模块只走白名单 `allowedDependencies` + Spring `ApplicationEventPublisher` 事件。

**测试覆盖**
后端 JaCoCo 95% 行覆盖硬门控（`./mvnw verify`）。前端 Vitest 边界分支要精准覆盖，不摆设。

**认证模型**
JWT 存内存（Zustand，不 persist），WS 走 CONNECT 帧 `Authorization: Bearer`。**不是 cookie**（老前端曾用 cookie，已废弃）。

## 语言

永远用中文回复。
