# 快速上手

> 10 分钟从 0 到 1:起后端 → 起前端 → 注册 → 接模拟盘账户 → 第一笔行情 → 第一笔下单 → 接 AI 跑通。
> 链路全程用**模拟盘账户**(余额 10 万 USDT,真实撮合模拟,可逆),验证完再切实盘。

## 前置

| 依赖 | 版本 | 验证 |
|---|---|---|
| JDK | 21+ | `java -version` |
| Node | 20+ | `node -v` |
| pnpm | 9+ | `pnpm -v` |
| Docker | 运行中 | `docker ps` |

## 1. 配 .env + 起 PostgreSQL

```bash
# 本地 dev 手写 .env。不要 cp .env.example——那是 prod 服务器模板
# (自带 SPRING_PROFILES_ACTIVE=prod,照抄会误激活 prod profile)
cat > .env << EOF
POSTGRES_PASSWORD=自己设一个
JWT_SECRET=$(openssl rand -base64 32)
ENCRYPTION_KEY=$(openssl rand -base64 32)
KWIKQUANT_MCP_PEPPER=$(openssl rand -base64 32)
EOF

docker compose -f docker/docker-compose.yml --project-directory . up -d   # 须带 --project-directory . 才读根 .env
docker compose -f docker/docker-compose.yml --project-directory . ps   # 期望 STATUS = healthy
```

`.env` 必填四项:`POSTGRES_PASSWORD` / `JWT_SECRET` / `ENCRYPTION_KEY` / `KWIKQUANT_MCP_PEPPER`,缺一后端 fail-fast 起不来。

## 2. 起后端

```bash
./scripts/start-backend.sh
# 等到控制台出现 "Started KwikquantApplication"
```

脚本会 source `.env` 并以 dev profile 启动;裸 `./mvnw spring-boot:run` 不读 `.env` 也没有默认 profile,起不来。

> **回测在 Docker 容器中执行**(dev 与 prod 同路径,镜像自带 Python 3.11 + 依赖,不依赖宿主 Python)。
> 首次需构建 worker 镜像并启动 worker 网络:
> ```bash
> docker compose -f docker/docker-compose.yml --project-directory . up -d   # PostgreSQL + kwikquant-worker-net 网络
> docker build -f docker/kwikquant-worker.Dockerfile -t kwikquant-worker:latest .
> ```

验证 MCP server 已暴露(PAT filter fail-closed,无 PAT 应返 401):

```bash
curl -i http://localhost:8080/mcp
# 期望:HTTP 401 + {"code":10001,"message":"mcp token invalid"}
```

## 3. 起前端

```bash
cd frontend && pnpm install && pnpm dev
# → http://localhost:5173(vite dev,proxy /api /ws 到 8080)
```

## 4. 注册 + 登录

前端 http://localhost:5173 → 注册 → 登录。dev 邀请码 `KWIK-DEV-001`(V20 迁移预置)。

> 正式环境:V20 预置的 dev 码请 `UPDATE invite_codes SET enabled=FALSE` 停用,
> 管理员用 SQL 生成正式码:`INSERT INTO invite_codes (code, max_uses) VALUES ('<自定义码>', 1);`

或 curl:

```bash
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","email":"alice@example.com","password":"alice12345","inviteCode":"KWIK-DEV-001"}'

curl -i -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"alice12345"}'
# 响应体:{"code":0,"data":{"accessToken":"eyJ...","expiresIn":900}}
# Set-Cookie: refresh_token=...; Path=/; HttpOnly
```

- JWT access token 15min 有效,前端放 `Authorization: Bearer <token>`
- refresh_token cookie 7d,过期前自动续(access token 失效时用 `/api/v1/auth/refresh`)

## 5. 接一个交易所账户(模拟盘起步)

模拟盘(PAPER)无需 API key,后端按公开行情模拟撮合,初始余额 10 万 USDT,适合验证链路:

```bash
JWT=<第 4 步拿到的 accessToken>

curl -X POST http://localhost:8080/api/v1/accounts \
  -H "Authorization: Bearer $JWT" -H "Content-Type: application/json" \
  -d '{"exchange":"OKX","label":"模拟盘","paperTrading":true,"testnet":false}'
# {"code":0,"data":{"id":2,"exchange":"OKX","label":"模拟盘","paperTrading":true,"status":"ACTIVE"}}
```

记下 `accountId`(如 `2`)。`exchange` 只接受 `OKX` / `BINANCE` / `BITGET`,不接受 `PAPER`——是否模拟盘由 `paperTrading` 字段决定,`exchange` 仅表示撮合/定价参考哪个公开行情。

接实盘 OKX testnet(可选,有真实 testnet 行情,成交不可逆但 testnet 资金是假的):

```bash
curl -X POST http://localhost:8080/api/v1/accounts \
  -H "Authorization: Bearer $JWT" -H "Content-Type: application/json" \
  -d '{"exchange":"OKX","label":"testnet","paperTrading":false,"testnet":true,
       "apiKey":"你的testnet-key","apiSecret":"你的testnet-secret","passphrase":"你的passphrase"}'
```

前端 UI 路径:Settings → 交易账户 → 新建,勾选模拟盘或填 testnet key。apiKey/secret 在后端 AES-256-GCM 加密存储,响应不返回明文。

## 6. 第一笔行情

前端 Market 页看 BTC/USDT ticker / K 线 / 盘口。

或装 CLI:

```bash
cd cli && pnpm install && pnpm build && npm link -g
kwikquant auth login alice alice12345
kwikquant quote BTC/USDT
# 交易对     最新价    买一     卖一     24h量
# BTC/USDT   64998.3  64998.3  64998.4  3239.45

kwikquant kline BTC/USDT -p 1h --limit 3
kwikquant depth BTC/USDT -d 5
kwikquant tickers --sort quoteVolume --limit 10
```

行情空 / 404?OKX 在国内需代理,在 `application-dev.yaml` 配 `kwikquant.proxy.defaults`(详见 [行情代理](#行情代理))。

## 7. 第一笔下单(模拟盘 SPOT)

前端 Trading 页:选模拟盘账户 → BTC/USDT → 市价买 0.001。

或 CLI:

```bash
kwikquant accounts list   # 拿到模拟盘 accountId
kwikquant order submit -a 2 -s BTC/USDT --side buy --type market --amount 0.001
# ✓ 订单已提交 orderId=42 status=FILLED

kwikquant positions        # 见 0.001 BTC 持仓
kwikquant history          # 交易历史
```

模拟盘成交可逆(可 `position close` 平掉重来)。实盘下单须 `--confirm`,真实成交不可逆。

## 8. 接 AI Claude Code

签 PAT(明文仅签发时返回一次,HMAC + pepper 哈希存储,丢失只能重签):

```bash
curl -X POST http://localhost:8080/api/v1/mcp/tokens \
  -H "Authorization: Bearer $JWT" -H "Content-Type: application/json" \
  -d '{"name":"claude-code"}'
# {"code":0,"data":{"token":"kq_pat_...","id":7}}  ← 立刻复制 token
```

或前端 Settings → MCP Tokens → 新建 → 复制明文 token。

接 Claude Code:

```bash
claude mcp add --transport http kwikquant http://localhost:8080/mcp \
  --header "Authorization: Bearer kq_pat_..."
claude mcp list   # 应见 kwikquant
```

新会话用自然语言测,确认工具被调用(不是 AI 自己编):

```
列出我的交易所账户           → 触发 list_accounts(响应无 apiKey 明文)
查 okx BTC/USDT 最新价       → 触发 get_ticker
在模拟盘上买 0.001 BTC 现货  → 触发 submit_order(FILLED)
```

## 9. PERP 永续合约(可选)

```bash
kwikquant order submit -a 2 -s BTC/USDT --side buy --type market --amount 0.01 \
  -m perp --margin-mode isolated --leverage 10
# 10x isolated 做多 0.01 BTC 永续

kwikquant positions        # PERP 持仓含 liquidationPrice / leverage / marginMode / 累计资金费
```

PERP 三参:`leverage`(1-125)/ `marginMode`(`isolated` | `cross`)/ `positionEffect`(CLI 自动派生,见 [cookbook PERP 篇](cookbook.md#perp-永续合约))。资金费率 8h 结算一次,强平有历史可查。

## 下一步

- [cookbook](cookbook.md) — 任务式 walkthrough(用 AI 下单 / 查行情 / 跑回测 / 接 MCP 各一篇)
- [cli-reference](cli-reference.md) — CLI 全命令参数 + 返回字段
- [mcp-setup](mcp-setup.md) — MCP 各客户端配置(Claude Code / Cursor / Zed / Gemini / Codex / Warp)
- [api-reference](api-reference.md) — REST 端点全表(从 OpenAPI 生成,防漂移)
- [llm-integration](llm-integration.md) — AI 接入四种方式选型

## 行情代理

OKX / Binance 在国内直连通常被封(451 / 超时)。代理走 yaml 配置(不走 `.env` 环境变量),在 `src/main/resources/application-dev.yaml` 已有的 `kwikquant:` 段下加:

```yaml
kwikquant:
  proxy:
    defaults:
      rest-proxy: http://127.0.0.1:7890   # 或你的本地代理端口
      ws-proxy: socks5://127.0.0.1:7890
```

重启后端生效。`overrides` 可按交易所覆盖全局(如 `BINANCE: { direct: true }`)。test JVM 在 `pom.xml` surefire 已禁代理(测试不依赖外网)。Bitget 通常直连可达,可作 fallback。

## 故障

| 现象 | 原因 | 解法 |
|---|---|---|
| 后端起不来 | `.env` 四项没填 / PG 没 healthy | `openssl rand` 生成;`docker compose ps` 看 healthy |
| 401 + code 10001 | JWT 过期 / PAT 无效或吊销 | 重新 `auth login` / 重签 PAT |
| 403 + code 1002 | accountId 不属于当前用户 | `accounts list` 查自己账户 |
| 400 + code 10002 | 枚举非法(exchange 大写 / marketType spot\|perp) | 看错误 message,改大小写 |
| 400 + code 10006 | MCP 高危写 confirmToken 无效(过期/已用/参数变了) | 不带 confirmToken 会返预览+令牌(非错误),复述相同参数带令牌重调 |
| 行情空 / 404 | OKX/Binance 需代理 | `application-dev.yaml` 配 `kwikquant.proxy.defaults` |
| 502 + code 6001 | 交易所限频 / 网络 | 换交易所(Bitget 直连)或加重试 |
| 200 + RISK_REJECTED | 风控拦截(非错误) | `risk policies` 查规则,调参后重试 |
| 回测报 docker 相关错误 | worker 镜像未构建 / 网络缺失 | `docker build -f docker/kwikquant-worker.Dockerfile -t kwikquant-worker:latest .`;`docker compose -f docker/docker-compose.yml --project-directory . up -d` 建网络 |
