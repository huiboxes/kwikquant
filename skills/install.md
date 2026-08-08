# 安装指南

把 KwikQuant 接入你的 AI 客户端(Claude Code / Cursor / Codex 等)。安装完成后,可对 AI 说:

- 「查 okx 永续 BTC/USDT 最新价和当前资金费率,我该不该持有这个多仓?」
- 「在模拟盘上,okx 市价单买 0.001 BTC/USDT 现货」
- 「回顾这个月我的组合:盈亏趋势、最大赢家、最大拖累」
- 「紧急停止所有运行中的策略」(须确认)

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

## 第 2 步:接入平台(CLI 与 MCP 二选一)

CLI 和 MCP 都是访问 KwikQuant 后端的方式,选一种:

### 方式 A:CLI(终端体验最佳)

AI 在终端直接跑 `kwikquant` 命令,适合 Claude Code / Codex / opencode / OpenClaw / Gemini CLI / Warp 等能跑 shell 的工具。

```bash
# 安装(本地构建)
cd cli && pnpm install && pnpm build && npm link -g

# 登录(JWT 存 ~/.kwikquant/credentials.json,0600)
kwikquant auth login <username> <password>
```

CLI 直连 REST(`/api/v1/**`),走 JWT 鉴权。命令参考见 [docs/cli-reference.md](../docs/cli-reference.md)。

### 方式 B:MCP(免本地安装,配置即用)

适合 Claude Desktop / Cursor / Zed / Gemini CLI / Warp 等支持 MCP 的工具,只需加一个 URL + PAT,无需本地装 CLI。

1. **签发 PAT**(明文仅一次):

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

2. **配置客户端**(详见 [docs/mcp-setup.md](../docs/mcp-setup.md)):

   | 客户端 | 配置位置 |
   |---|---|
   | Claude Code | `claude mcp add --transport http kwikquant http://localhost:8080/mcp --header "Authorization: Bearer <PAT>"` |
   | Claude Desktop | `~/Library/Application Support/Claude/claude_desktop_config.json` 的 `mcpServers` |
   | Cursor | `.cursor/mcp.json` 或全局配置的 `mcpServers` |
   | Zed | `~/.config/zed/settings.json` 的 `context_servers` |
   | Gemini CLI | `~/.gemini/settings.json` 的 `mcpServers` |
   | Codex Desktop | Settings → MCP Servers → Add Server(Streamable HTTP) |
   | Warp | Settings → AI → MCP Servers → Add |

   Claude Code JSON 形态:
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

## 第 3 步:安装 Skill(让 AI 知道 KwikQuant 能做什么)

Skill 是一套指令文件,告诉 AI 助手 KwikQuant 能做什么、怎么用。三种安装方式:

### 方式 A:包管理器(推荐)

```bash
npx skills add kwikquant/skills -g
# 或 bunx skills add kwikquant/skills -g
```

### 方式 B:Claude Code / Codex 插件市场

```
/plugin marketplace add kwikquant/skills
/plugin install kwikquant@kwikquant-skills
```

### 方式 C:复制提示词(最简)

复制以下内容发给任意 AI,它会引导你完成安装:
```
请按照以下指南安装 KwikQuant AI toolkit:
https://kwikquant.dev/skill/install.md

安装完成后,完成登录授权,查询 BTC/USDT 行情确认可用。
```

## 第 4 步:验证安装

重启 AI 客户端,在新会话里:

```
列出我的交易所账户
```

- MCP 路径:Agent 应调 `list_accounts` 工具,返回账户列表(不含 apiKey)
- CLI 路径:Agent 应跑 `kwikquant accounts list`

看到工具/命令被调用即安装成功。继续验证交易闭环(建议先用**模拟盘账户**):

```
查 okx 永续 BTC/USDT 最新价
```
→ 触发 `get_ticker` / `kwikquant quote BTC/USDT -m perp`

```
在账户 1 上,用市价单在 okx 买 0.001 BTC/USDT 现货
```
→ 触发 `submit_order`(实盘须 `confirm=true`)

## 故障排查

| 现象 | 原因 | 解法 |
|---|---|---|
| 401 + code 10001 | PAT 无效 / 未配 Authorization | 重新签发 PAT,确认 `Bearer ` 前缀 + 空格 |
| 工具不出现 | MCP server 未启动 / 配置未加载 | `curl http://localhost:8080/mcp` 看 401;重启客户端 |
| 403 + code 1002 | accountId 不属于当前用户 | `list_accounts` 查自己账户,换正确 accountId |
| 400 + code 10002 | 枚举值非法 | exchange 小写 binance/okx/bitget;marketType 用 spot/perp |
| 400 + code 10004 | 高危操作缺 confirm | start_live_trading / emergency_stop 须显式 confirm=true |
| 200 + status=RISK_REJECTED | 风控拒绝(非错误) | 查风控规则,调参后重试 |
| 502 + code 6001 | 交易所 API 失败 | 限频 / 网络 / 代理(.env CCXT_PROXY) |

### 客户端特殊限制

- **Claude Desktop Chat/Cowork 模式**:有网络白名单,阻止 CLI 安装与 MCP 连接。切换到 **Code** tab(内嵌 Claude Code)操作。
- **Codex Cloud 模式**:同样限制。新建会话选 **Work locally** 而非 Cloud。
- **Claude.ai / ChatGPT.com 网页版**:无本地系统访问,不能跑 shell 或连外部 MCP。用 Claude Desktop Code tab 或本地 CLI 工具。

## 安全提醒

- PAT / JWT 等同账户密码,**不要提交到 git**,不要贴在公开 issue
- 写操作(下单 / 平仓 / 启动策略)在**实盘账户真实成交不可逆**,先用模拟盘账户验证
- `emergency_stop` 会停所有 RUNNING 策略,`confirm=true` 才执行
- 定期查 `GET /api/v1/mcp/tokens` 吊销不用的 PAT

## 撤销授权

- PAT:前端 Settings → MCP Tokens 删除,或 `DELETE /api/v1/mcp/tokens/{id}`
- CLI:`kwikquant auth logout` 清除本地 JWT

## 后续:公网分发(当前未做)

本项目目前仅 `localhost`。要让外部用户安装(像长桥那样填一个 URL 即用),需:
1. 部署到公网(云主机 / 内网穿透)+ 域名 + HTTPS
2. 把 install.md / mcp-setup.md 里的 `http://localhost:8080/mcp` 换成公网 URL
3. 打包 `skills/` 为 ZIP 发到 GitHub Release
这是当前阶段的扩展项,不影响本地验证"真实落地"。
