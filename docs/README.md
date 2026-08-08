# kwikquant 项目文档

## 接入文档(面向开发者,对标长桥 docs/)

- [安装指南](../skills/install.md) — CLI / MCP 二选一 + Skill 安装 + 各客户端配置
- [CLI 命令参考](cli-reference.md) — 行情 / 账户 / 组合 / 订单 / 策略 / 风控全命令
- [MCP 接入](mcp-setup.md) — Claude Code / Cursor / Zed / Gemini / Codex / Warp 配置
- [LLM / AI 集成](llm-integration.md) — 四接入选型 + LLMs Text 标准
- [变更日志](changelog.md) — 版本记录
- [llms.txt](llms.txt) — 机读文档索引(供 RAG / Cursor Custom Docs)

## 权威文档（当前有效）

- [产品全景](product-direction.md) — v2.1，产品定位/架构/全模块定义，唯一权威来源
- [架构与约束](architecture-and-constraints.md) — Coding Agent 必读，关键边界与踩坑预警
- [实施计划](implementation-plan.md) — v2.1，Wave 1-10 已全部完成
- [技术债清单](tech-debt.md) — 已识别暂不修复的技术债与处理决策

## 历史文档（部分有效）

- [技术选型](.archived/phase-0-tech-selection.md) — 大部分有效，XChange → CCXT 变更需注意
- [需求定义](.archived/phase-1-requirements.md) — ❌ 已被 product-direction.md v2.1 取代
- [系统设计](.archived/phase-2-system-design.md) — 订单流程/风控/审计/认证仍有效；策略引擎部分作废
