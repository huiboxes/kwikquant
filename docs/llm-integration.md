# LLM / AI 集成

KwikQuant 为 LLM(Large Language Models)与 AI Agent 提供四种接入方式,覆盖从自然语言对话到程序化交易的完整链路。对标长桥 LLMs 组件,聚焦加密域。

## 接入方式选型

| 方式 | Best for | 鉴权 |
|---|---|---|
| **MCP** | AI 编码助手(Claude Code / Cursor / Codex / Gemini / Zed) | PAT(Bearer token,HMAC + pepper) |
| **AI Skill** | 给任意 AI 直接知识(Longbridge Skill 风格的分包指令) | 走 MCP 或 CLI |
| **CLI** | 终端工作流 / 脚本 / AI Agent 工具调用 | JWT |
| **REST + WebSocket** | 任意语言程序化集成 / 自建 dashboard | JWT |

## MCP(Model Context Protocol)

Streamable HTTP server,23 工具按 5 域(行情 / 账户 / 下单持仓 / 策略回测 / 风控)动态发现。详见 [MCP 接入](mcp-setup.md)。

```bash
claude mcp add --transport http kwikquant http://localhost:8080/mcp \
  --header "Authorization: Bearer <YOUR_PAT>"
```

接入后,AI 可在自然语言对话中查行情、看持仓、下单(高危 confirm 二次确认)。

## AI Skill

5 个 Anthropic Agent Skill 按域分包,是一套告诉 AI「KwikQuant 能做什么 + 怎么用」的指令文件。详见 [Skill 目录](../skills/README.md)。

```bash
npx skills add kwikquant/skills -g
```

Skill 本身不含鉴权——它指导 AI 调用 MCP 工具或 CLI 命令完成实际操作。

## CLI

AI 原生命令行,覆盖行情 / 账户 / 组合 / 持仓 / 订单 / 策略 / 回测 / 风控 / 交易历史等交易域端点(报告、AI 对话、通知等域暂未覆盖),`--format json` 可管道 jq / awk 或喂给 AI Agent 工具通道。详见 [CLI 命令参考](cli-reference.md)。

```bash
kwikquant auth login <user> <pass>      # JWT
kwikquant quote BTC/USDT --format json | jq '.[0].last'
```

## REST + WebSocket

生产级 HTTP/WS 接口,任意语言可接。响应统一 `ApiResponse` 信封 `{code, message, data}`,金额 `BigDecimal` 序列化为 string。

```bash
curl -H "Authorization: Bearer $JWT" \
  http://localhost:8080/api/v1/market/ticker/OKX/SPOT/BTC-USDT
```

```js
const ws = new WebSocket('ws://localhost:8080/ws')
ws.send(JSON.stringify({ destination: '/topic/ticker/OKX/SPOT/BTC-USDT' }))
```

详见 [WebSocket 契约](ws-contract.md) 与 [行为契约](behavior-contract.md)。

## LLMs Text 标准

KwikQuant 文档遵循 [LLMs Text](https://llmstxt.org) 标准,提供 `llms.txt` 机读索引,每个文档页可作 Markdown 检索:

- 机读索引:`docs/llms.txt`
- API 契约:OpenAPI 3(`/v3/api-docs`,Springdoc 3.0.3)
- WebSocket 契约:[ws-contract.md](ws-contract.md)
- 行为契约:[behavior-contract.md](behavior-contract.md)

可用于 Cursor Custom Docs / RAG 索引 / 工具化内容摄取。

### 在 Cursor 中接入

打开 Cursor 命令面板(`Cmd+Shift+P`),搜索 Add New Custom Docs,填入 `docs/llms.txt` 路径(或公网分发后的 `https://kwikquant.dev/llms.txt`)。添加后,在 AI 对话中通过 `@docs` 菜单 `Add Context` 选中这些文档作为上下文。

## 场景示例

安装完成后,可对 AI 助手说:

- 「查 okx 永续 BTC/USDT 最新价和当前资金费率,我该不该持有这个多仓?」
- 「在模拟盘上,okx 市价单买 0.001 BTC/USDT 现货」
- 「回顾这个月我的组合:盈亏趋势、最大赢家、最大拖累、模拟盘 vs 实盘配置」
- 「查 okx 永续 BTC/USDT 过去 6 个月日线,看是否该持有」
- 「紧急停止所有运行中的策略」(须 `confirm=true`)

交易类操作 AI 应先征求人类确认;高危操作(实盘启动 / 紧急停止)后端强制 `confirm=true`。
