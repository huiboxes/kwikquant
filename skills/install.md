# 安装指南

把 KwikQuant MCP server 接入你的 AI 客户端(Claude Code / Cursor / Codex 等)。

## 前置条件

- KwikQuant 后端运行中(默认 `http://localhost:8080`)
- 一个已注册账户(JWT 登录后才能签发 PAT)
- 已连接至少一个交易所账户(SPOT 或 PERP,模拟盘或实盘)

## 第 1 步:启动后端

```bash
# 启动 PostgreSQL
docker compose -f docker/docker-compose.yml up -d

# 配置 .env(CCXT_PROXY 等),启动后端
./mvnw spring-boot:run
```

验证 MCP server 已暴露(filter fail-closed,无 PAT 应返 401):
```bash
curl -i http://localhost:8080/mcp
# 期望:401 + {"code":10001,...}  ← 证明 PAT filter 生效
```

## 第 2 步:签发 PAT

PAT(Personal Access Token)是访问 MCP server 的个人访问令牌,**明文仅签发时返回一次**,通过 HMAC + pepper 哈希存储,丢失只能重新签发。

**方式 A(前端 UI,推荐)**:登录前端 → Settings → MCP Tokens → 新建 → 复制明文 token。

**方式 B(REST)**:用 JWT 调 `POST /api/v1/mcp/tokens`:
```bash
# 1. 登录拿 access_token
JWT=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"yourname","password":"yourpass"}' | jq -r '.data.accessToken')

# 2. 签发 PAT(字段名以实际响应为准,此处取 plainToken)
curl -s -X POST http://localhost:8080/api/v1/mcp/tokens \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"name":"claude-code-bot"}' | jq -r '.data.token'
# → 复制 token 字段值(明文,仅此一次,列表接口不再返回明文)
# 实测响应字段名是 token(非 plainToken),如 {id,name,token,createdAt}
```

PAT 管理其他端点:
- `GET /api/v1/mcp/tokens` — 列表(仅元信息,无明文)
- `DELETE /api/v1/mcp/tokens/{id}` — 吊销

## 第 3 步:配置 AI 客户端

### Claude Code

**方式 A(CLI,推荐)**:
```bash
claude mcp add --transport http kwikquant http://localhost:8080/mcp \
  --header "Authorization: Bearer YOUR_PAT_HERE"
```

**方式 B(编辑 `~/.claude.json`)**:
```json
{
  "mcpServers": {
    "kwikquant": {
      "type": "http",
      "url": "http://localhost:8080/mcp",
      "headers": {
        "Authorization": "Bearer YOUR_PAT_HERE"
      }
    }
  }
}
```

> 配置语法以你本地 Claude Code 版本为准(`claude mcp --help` 查最新)。

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

## 第 4 步:验证安装

重启 Claude Code,在新会话里:

```
列出我的交易所账户
```

Agent 应调 `list_accounts` 工具,返回账户列表(不含 apiKey)。看到工具被调用即安装成功。

继续验证交易闭环(建议先用**模拟盘账户**):

```
查 okx 永续 BTC/USDT 最新价
```
→ 触发 `get_ticker`。

```
在账户 1 上,用市价单在 okx 买 0.001 BTC/USDT 现货
```
→ 触发 `submit_order`。

## 故障排查

| 现象 | 原因 | 解法 |
|---|---|---|
| 401 + code 10001 | PAT 无效 / 未配 Authorization | 重新签发 PAT,确认 `Bearer ` 前缀 + 空格 |
| 工具不出现 | MCP server 未启动 / 配置未加载 | `curl http://localhost:8080/mcp` 看 401;重启 Claude Code |
| 403 + code 1002 | accountId 不属于当前 PAT 用户 | `list_accounts` 查自己账户,换正确 accountId |
| 400 + code 10002 | 枚举值非法 | exchange 小写 binance/okx/bitget;marketType 用 spot/perp |
| 400 + code 10004 | 高危操作缺 confirm | start_live_trading / emergency_stop 须显式 confirm=true |
| 200 + status=RISK_REJECTED | 风控拒绝(非错误) | 查风控规则,调参后重试 |
| 500 + code 6001 | 交易所 API 失败 | 限频 / 网络 / 代理(.env CCXT_PROXY) |

## 安全提醒

- PAT 等同账户密码,**不要提交到 git**,不要贴在公开 issue
- 写操作(下单 / 平仓 / 启动策略)在**实盘账户真实成交不可逆**,先用模拟盘账户验证
- `emergency_stop` 会停所有 RUNNING 策略,`confirm=true` 才执行

## 后续:公网分发(当前未做)

本项目目前仅 `localhost`。要让外部用户安装(像长桥那样填一个 URL 即用),需:
1. 部署到公网(云主机 / 内网穿透)+ 域名 + HTTPS
2. 把 install.md 里的 `http://localhost:8080/mcp` 换成公网 URL
3. 打包 `skills/` 为 ZIP 发到 GitHub Release
这是 Step 1 之后的扩展项,不影响本地验证"真实落地"。
