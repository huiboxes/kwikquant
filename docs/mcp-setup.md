# MCP 接入

KwikQuant 通过 MCP(Model Context Protocol)server 暴露 **23 个工具**,按 5 个域分包,可被 Claude Code / Cursor / Codex / Gemini / Zed 等任意支持 MCP 的 AI 客户端接入。对标长桥托管 MCP 架构,聚焦加密域(SPOT + PERP)。

## MCP endpoint

- 本地:`http://localhost:8080/mcp`
- 协议:Streamable HTTP(`POST /mcp`),Spring AI 2.0 server
- 鉴权:PAT(Personal Access Token),`Authorization: Bearer <PAT>`

## 能力

| 域 | 工具数 | 能力 |
|---|---|---|
| 行情数据 | 4 | K 线 / 最新价 / 盘口 / 资金费率 |
| 账户与组合 | 4 | 账户 / 余额 / 组合 / 交易历史 |
| 下单与持仓 | 7 | 下单 / 撤单 / 持仓 / 平仓 / 资金费历史 / 强平历史 |
| 策略与回测 | 5 | 回测 / 对比 / 模拟盘 / 实盘 |
| 风控 | 3 | 查 / 设规则 / 紧急停止 |

客户端连接时自动发现工具,无需手动配置。

## 前置条件

1. KwikQuant 后端运行中(`./mvnw spring-boot:run`,MCP server 暴露在 `http://localhost:8080/mcp`)
2. 已注册账户 + 至少一个交易所账户(模拟盘或实盘)
3. 签发 PAT(见下)

## 签发 PAT

PAT 是访问 MCP server 的个人访问令牌,**明文仅签发时返回一次**,HMAC + pepper 哈希存储,丢失只能重新签发。

**方式 A(前端 UI,推荐)**:登录前端 → Settings → MCP Tokens → 新建 → 复制明文 token。

**方式 B(REST)**:
```bash
JWT=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"yourname","password":"yourpass"}' | jq -r '.data.accessToken')

curl -s -X POST http://localhost:8080/api/v1/mcp/tokens \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"name":"claude-code-bot"}' | jq -r '.data.token'
```

PAT 管理端点:`GET /api/v1/mcp/tokens`(列表,无明文)、`DELETE /api/v1/mcp/tokens/{id}`(吊销)。

## 客户端配置

### Claude Code

```bash
claude mcp add --transport http kwikquant http://localhost:8080/mcp \
  --header "Authorization: Bearer <YOUR_PAT>"
```

或编辑 `~/.claude.json`:
```json
{
  "mcpServers": {
    "kwikquant": {
      "type": "http",
      "url": "http://localhost:8080/mcp",
      "headers": { "Authorization": "Bearer YOUR_PAT_HERE" }
    }
  }
}
```

验证:`claude mcp list` → `kwikquant ✓ ready 23 tools`

### Cursor

编辑 `.cursor/mcp.json`(项目级)或全局配置:
```json
{
  "mcpServers": {
    "kwikquant": {
      "url": "http://localhost:8080/mcp",
      "headers": { "Authorization": "Bearer YOUR_PAT_HERE" }
    }
  }
}
```

### Zed

`context_servers` key in `~/.config/zed/settings.json`:
```json
{
  "context_servers": {
    "kwikquant": {
      "url": "http://localhost:8080/mcp",
      "headers": { "Authorization": "Bearer YOUR_PAT_HERE" }
    }
  }
}
```

### Gemini CLI

`mcpServers` key in `~/.gemini/settings.json`(同 Zed 格式)。

### Codex

```bash
codex mcp add kwikquant --url http://localhost:8080/mcp
```
首次调用触发 PAT 鉴权(Codex 桌面版:Settings → MCP Servers → Add Server,Type=Streamable HTTP,URL + Authorization header)。

### Warp

Settings → AI → MCP Servers → Add,填 URL + Authorization header。

## 配置位置速查

| 客户端 | 配置位置 |
|---|---|
| Claude Code | `claude mcp add` CLI 或 `~/.claude.json` |
| Cursor | `.cursor/mcp.json` 或全局配置 |
| Zed | `~/.config/zed/settings.json` 的 `context_servers` |
| Gemini CLI | `~/.gemini/settings.json` 的 `mcpServers` |
| Codex Desktop | Settings → MCP Servers → Add Server |
| Warp | Settings → AI → MCP Servers → Add |

## 安全建议

- **最小权限**:只签发当前任务所需 PAT,用完吊销
- **交易确认**:下单 / 平仓 / 实盘启动 / 紧急停止,在 prompt 里显式要求 AI 先征求人类确认
- **凭证处理**:PAT 不复制到不受信环境,不提交 git,不贴公开 issue
- **定期审查**:`GET /api/v1/mcp/tokens` 查 PAT 列表,吊销不用的

## 故障排查

| 现象 | 原因 | 解法 |
|---|---|---|
| 401 + code 10001 | PAT 无效 / 未配 Authorization | 重新签发 PAT,确认 `Bearer ` 前缀 + 空格 |
| 工具不出现 | MCP server 未启动 / 配置未加载 | `curl http://localhost:8080/mcp` 看 401;重启客户端 |
| 403 + code 1002 | accountId 不属于当前 PAT 用户 | `list_accounts` 查自己账户,换正确 accountId |
| 400 + code 10002 | 枚举值非法 | exchange 小写 binance/okx/bitget;marketType 用 spot/perp |
| 400 + code 10004 | 高危操作缺 confirm | start_live_trading / emergency_stop 须显式 confirm=true |
| 200 + status=RISK_REJECTED | 风控拒绝(非错误) | 查风控规则,调参后重试 |
| 502 + code 6001 | 交易所 API 失败 | 限频 / 网络 / 代理(.env CCXT_PROXY) |
