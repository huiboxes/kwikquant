# kwikquant 项目文档

## 接入文档(面向开发者,对标长桥 docs/)

- [快速上手](quickstart.md) — 10 分钟从 0 到 1(后端 → 注册 → 模拟盘下单 → 接 AI)
- [Cookbook 任务式指南](cookbook.md) — 按「我想做 X」组织(查行情 / 下单 / PERP / 跑回测 / 用 AI)
- [安装指南](../skills/install.md) — CLI / MCP 二选一 + Skill 安装 + 各客户端配置
- [CLI 命令参考](cli-reference.md) — 行情 / 账户 / 组合 / 订单 / 策略 / 风控全命令
- [MCP 接入](mcp-setup.md) — Claude Code / Cursor / Zed / Gemini / Codex / Warp 配置
- [LLM / AI 集成](llm-integration.md) — 四接入选型(MCP / Skill / CLI / REST+WS)
- [REST API 参考](api-reference.md) — 71 端点全表(OpenAPI 自动生成,防漂移)
- [WebSocket 契约](ws-contract.md) — 推送 destination / schema / 心跳 / 重连
- [端点行为契约](behavior-contract.md) — 语义 / 错误码 / 特殊响应
- [策略 API 参考](strategy-api.md) — StrategyContext 统一契约(策略作者参考:ctx 签名 / OrderAck / 金额红线 / PERP 四向 / PARAMS / 三运行时能力矩阵)
- [撮合语义规范](matching-spec.md) — 回测与模拟盘撮合的单一真相源(Java / Python 双实现,差分 fixtures 对拍)
- [PERP 数学规范](perp-math-spec.md) — 保证金 / 强平价 / 资金费 / 张↔币换算公式的单一真相源(Java PerpMath / Python perp_math 对拍)
- [PERP 回测规范](perp-backtest-spec.md) — PERP 回测账本 / bar 极值强平近似 / 资金费期次回放语义的单一真相源

## 运维

- [部署手册](deploy.md) — CI 镜像发布 / 服务器部署 / 日常发版 / 回滚 / 运维

## AI 友好(Anthropic llms.txt proposal)

- [llms.txt](llms.txt) — 站点大纲索引(给 AI crawler / RAG / Cursor Custom Docs)
- [llms-full.txt](llms-full.txt) — 全量单页 markdown(docs 9 篇 + skills 2 篇合并,AI agent 一次读完能用)
- OpenAPI 3 规范:运行时 `http://localhost:8080/v3/api-docs`(Springdoc 3.0.3)

生成:改文档后跑 `node frontend/scripts/gen-api-reference.mjs` + `node frontend/scripts/gen-llms-full.mjs` 重新生成 api-reference 与 llms-full。

