# kwikquant 项目文档

## 接入文档(面向开发者,对标长桥 docs/)

- [快速上手](quickstart.md) — 10 分钟从 0 到 1(后端 → 注册 → 模拟盘下单 → 接 AI)
- [Cookbook 任务式指南](cookbook.md) — 按「我想做 X」组织(查行情 / 下单 / PERP / 跑回测 / 用 AI)
- [安装指南](../skills/install.md) — CLI / MCP 二选一 + Skill 安装 + 各客户端配置
- [CLI 命令参考](cli-reference.md) — 行情 / 账户 / 组合 / 订单 / 策略 / 风控全命令
- [MCP 接入](mcp-setup.md) — Claude Code / Cursor / Zed / Gemini / Codex / Warp 配置
- [LLM / AI 集成](llm-integration.md) — 四接入选型(MCP / Skill / CLI / REST+WS)
- [REST API 参考](api-reference.md) — 63 端点全表(OpenAPI 自动生成,防漂移)
- [WebSocket 契约](ws-contract.md) — 推送 destination / schema / 心跳 / 重连
- [端点行为契约](behavior-contract.md) — 语义 / 错误码 / 特殊响应
- [变更日志](changelog.md) — 版本记录

## AI 友好(Anthropic llms.txt proposal)

- [llms.txt](llms.txt) — 站点大纲索引(给 AI crawler / RAG / Cursor Custom Docs)
- [llms-full.txt](llms-full.txt) — 全量单页 markdown(8 文档合并,AI agent 一次读完能用)
- OpenAPI 3 规范:运行时 `http://localhost:8080/v3/api-docs`(Springdoc 3.0.3)

生成:改文档后跑 `node frontend/scripts/gen-api-reference.mjs` + `node frontend/scripts/gen-llms-full.mjs` 重新生成 api-reference 与 llms-full。

## 权威文档（当前有效）

- [产品全景](product-direction.md) — v2.1，产品定位/架构/全模块定义，唯一权威来源
- [架构与约束](architecture-and-constraints.md) — Coding Agent 必读，关键边界与踩坑预警
- [实施计划](implementation-plan.md) — v2.1，Wave 1-10 已全部完成
- [技术债清单](tech-debt.md) — 已识别暂不修复的技术债与处理决策

## 历史文档（部分有效）

- [技术选型](.archived/phase-0-tech-selection.md) — 大部分有效，XChange → CCXT 变更需注意
- [需求定义](.archived/phase-1-requirements.md) — ❌ 已被 product-direction.md v2.1 取代
- [系统设计](.archived/phase-2-system-design.md) — 订单流程/风控/审计/认证仍有效；策略引擎部分作废
