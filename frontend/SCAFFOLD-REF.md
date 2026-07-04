# 前端脚手架搭建参考清单

> **用途**:搭建新项目 `frontend/` 脚手架时,对照本文从老前端 `/Users/huibox/codeSpace/2026/nomad/kwikquant/frontend` 捞文件。一次性参考,脚手架搭完可删,不作为长期约束文档。
>
> **来源**:2026-07-04 盘点老前端 + 对照新项目 `docs/ux-design.md`、`docs/ws-contract.md`、新后端 `OrderStatus`/`ErrorCode`。
>
> **范围**:只管脚手架阶段(构建链 + 契约链 + Done AI token + 占位首屏)。http/ws/auth/业务页都不进脚手架,等业务阶段按新后端重写。

---

## 背景:老前端为什么不能照搬

老前端 UI 差的根因**不是代码,是约束**:`eslint-plugin-design-tokens` 那种"禁用清单" + 老 `CLAUDE.md` 那套"架构规则/交易红线"互相冲突的死规则,逼得 LLM 产出丑(详见记忆:约束要少而原则化,死规则致产出丑)。

所以本次脚手架**只搬工程地基,不搬视觉、不搬死约束**。视觉按 `docs/ux-design.md`(Done AI 品牌手册,2026-07-04 已 override 锁定)全新注入。

---

## ✅ 直接搬(脚手架照搬,零风险)

跟视觉、跟契约都无关,纯工程地基,老前端验证过能跑通。

| # | 资产 | 老前端路径 | 备注 |
|---|------|-----------|------|
| 1 | **package.json 依赖基线** | `package.json` | pnpm 10.33 + Node ≥22.12。依赖清单(React 19 / Vite 8 / Tailwind v4 / shadcn / Zustand / React Query / decimal.js / lightweight-charts / react-hook-form / zod / openapi-typescript / MSW / Playwright / Vitest)跟新 `ux-design.md` 栈一致,照搬依赖项 |
| 2 | **Vite 配置骨架** | `vite.config.ts` | `@` alias + Tailwind v4 plugin + Vitest jsdom 配置。dev proxy 的 `/api`、`/ws` 目标按新后端调;SockJS 的 `global: 'globalThis'` 那行**删掉**(新后端用原生 WS,不是 SockJS) |
| 3 | **tsconfig.app.json** | `tsconfig.app.json` | `strict` + `noUnusedLocals/Params` + `verbatimModuleSyntax` + `moduleResolution: bundler`,金融前端该有的严格度都有,照搬 |
| 4 | **shadcn components.json** | `components.json` | new-york 风格 + Tailwind v4 + lucide + `@/` alias,跟新栈一致 |
| 5 | **Vitest setup** | `src/test/setup.ts` | jest-dom + afterEach cleanup,三行搞定,照搬 |
| 6 | **Playwright E2E 框架** | `playwright.config.ts` | 唯一值得照搬的"原则":**真实后端联调,后端没起就 fail,不退化为 mock**。globalSetup 种子数据的模式也对。webServer 命令按 pnpm 调 |
| 7 | **gen:api 契约链** | `package.json` scripts | `gen:api` + `gen:api:check`(openapi-typescript 从后端 `/v3/api-docs` 生 `api-gen.ts` + CI drift 检查)。新 spec 硬原则 1,必须搬 |
| 8 | **types/README.md** | `src/types/README.md` | 把"BigDecimal → string,前端必须 decimal.js 接收"这条告警搬过去(契约核心红线) |
| 9 | **ESLint 金额红线(只这一条)** | `eslint.config.js` 的 `no-restricted-syntax` | 拦 `parseFloat` / `Number` 参与金额运算。金融前端命脉,新 spec 也写明金额用 decimal.js。**只搬这一条规则,别搬老 eslint.config 里其他东西** |
| 10 | **`lib/money.ts`** | `src/lib/money.ts` | `toDecimal` + `formatMoney`,纯函数跟视觉契约都无关。注释里"非法值抛错不静默归零"的论证很扎实,照搬 |
| 11 | **`lib/freshness.ts`** | `src/lib/freshness.ts` | `isStale` + `freshnessLabel`,30s 阈值纯函数,照搬 |
| 12 | **`lib/query-options.ts`** | `src/lib/query-options.ts` | `defaultRetry`(401/403 不重试,其他重试 2 次),照搬 |
| 13 | **`stores/themeStore.ts` 深浅色切换骨架** | `src/stores/themeStore.ts` | `colorScheme` 应用到 `<html class="dark">` 的纯 DOM 函数 + persist。**只取深浅色切换机制**;涨跌色 intl/cn 切换器看你要不要(新视觉涨跌色是 `#2BA298`/`#F63969`,色值要换) |

---

## ⚠️ 参考但别照抄(脚手架阶段不实现,业务阶段借鉴思路重写)

老前端这些"技巧"很好,但绑定的契约/模型跟新后端冲突。**脚手架阶段先不实现**,等业务阶段按新后端重写时,借鉴思路别从零写。

| # | 资产 | 借鉴什么 | 为什么不能照抄 |
|---|------|----------|----------------|
| 14 | **`lib/http.ts` fetch 封装** | "单飞刷新"(并发 401 共享一次 refresh)+"双解包"(auth 裸 body vs ApiResponse 包装)+"Network failure 包 ApiError" 三个技巧 | 老的是 httpOnly cookie + refresh-cookie;新 spec 是 **JWT 存内存 Zustand**。鉴权模型不同,重写 |
| 15 | **`api/errors.ts` ApiError 类** | `ApiError` 类 + `isUnauthorized`/`isForbidden` getter 的模型 | ErrorCode 常量要按新后端重填(见下方"权威枚举"段,已确认) |
| 16 | **`hooks/useWebSocket.ts`** | debounce invalidate 订单列表 + 增量 patch 持仓 + 新持仓首次出现就全量 refetch——这套增量同步逻辑非常成熟 | topic 全变(新 9 topic `/topic/orders/{userId}` 等,见 `ws-contract.md`)、鉴权全变(新 `/ws-native` + CONNECT 帧 `Authorization: Bearer`)、payload 全变(symbol 字符串 vs instrumentId 数字)。骨架留,内脏全换 |
| 17 | **`stores/authStore.ts` 三态机** | `unknown → authenticated → anonymous` 三态机很干净 | 新模型要存 in-memory access token,要加字段;"不 persist"原则**保留**(新 spec 也说"不放 localStorage") |
| 18 | **`hooks/useThemeHydrate` + App 入口结构** | "App 入口挂无渲染 hook 做副作用"模式 | 模式搬,具体 hook 看业务阶段接什么 |

---

## ❌ 别搬(老前端 UI 烂的根源,正是这些)

| # | 别搬项 | 为什么别搬 |
|---|--------|-----------|
| ❌1 | **`src/index.css` 的设计 token** | 老的是"Carbon 深色 + 青蓝 accent + 8px 小圆角零光效",你 2026-07-04 已 override 成 Done AI 暖铜色 + 24-32px 大圆角。两套完全相反。脚手架按 Done AI 全新注入(色板见 `docs/ux-design.md` 视觉段 + `docs/design-ref/done-ai/index.html` 的 `@theme` 块) |
| ❌2 | **`eslint-plugin-design-tokens/index.js` 自定义 ESLint 插件** | 老插件禁 `rounded-xl`/`rounded-2xl`/`backdrop-blur`(强制小圆角零光效)。新视觉正好相反要大圆角卡片。**插件机制可以借鉴**(拦截 className token 的写法巧妙),但禁用清单不能搬。**脚手架阶段干脆不引入**,等视觉稳定后再决定要不要 |
| ❌3 | **老 `CLAUDE.md` 那套"架构规则"+"交易红线"清单** | 老前端约束太死的标本(约束要少而原则化,死规则致 LLM 产出丑)。新项目已有 `docs/ux-design.md` 的"三条根本原则 + 其余皆派生",别再堆死清单 |
| ❌4 | **`docs/design-direction.md`** | 老视觉规范,已被新 `docs/ux-design.md` 取代 |
| ❌5 | **`components/ui/` 7 个 shadcn 组件** | 逻辑可留但 className 按老 token。不如直接 `shadcn add` 重生成,套新 token 更快 |
| ❌6 | **业务页/路由/布局**(`routes.tsx` / `pages/` / `components/trade|lab|portfolio|layout`) | 老 IA 是 module-per-tab,新 IA 是 Dashboard 策略舰队 + 工作区连续流,完全重定义。不进脚手架 |
| ❌7 | **`components/charts/` 如果用了 Recharts** | `ux-design.md` 明确不引 Recharts,lightweight-charts 兼做 K线 + equity line series |

---

## 权威枚举(业务阶段填 C3/C4/C15 时用,脚手架阶段先存着)

脚手架阶段用不到,但业务阶段填订单状态映射 / ErrorCode 字典 / ApiError 时,**别从老前端抄,也别从 ws-contract 抄**(ws-contract 是事件面简化),以新后端代码为准:

### OrderStatus(9 态,新后端权威)

源:`src/main/java/com/kwikquant/shared/types/OrderStatus.java`

```
NEW / PENDING_NEW / SUBMITTED / PARTIALLY_FILLED / FILLED /
PENDING_CANCEL / CANCELLED / REJECTED / EXPIRED
```

- 跟老前端 11 态比:少了 `ACCEPTED`/`EXCHANGE_ACCEPTED`/`INTERNAL_REJECTED`/`EXCHANGE_REJECTED`;`CANCELLED` 双写 l(老的 `CANCELED` 单 l);新增 `PENDING_NEW`/`PENDING_CANCEL`;`REJECTED` 单一态不再区分内部/交易所。
- 跟 `ws-contract.md §3.3` 5 态比:ws-contract 是**事件面简化**(`NEW|PARTIAL|FILLED|CANCELLED|REJECTED`),真实枚举是 9 态。**以这 9 态为准**,ws 事件 status 字段需要做映射(`PARTIAL`→`PARTIALLY_FILLED` 等)。

### ErrorCode(完整分段,新后端权威)

源:`src/main/java/com/kwikquant/shared/infra/ErrorCode.java`

```
0      SUCCESS
1xxx   认证/授权  1001 UNAUTHENTICATED / 1002 FORBIDDEN
20xx   风控       2001 RISK_REJECTED / 2002 INSUFFICIENT_MARGIN / 2010 POLICY_NOT_FOUND / 2011 POLICY_CONFLICT
3xxx   参数校验   3001 VALIDATION_FAILED
4xxx   通用资源   4001 NOT_FOUND / 4002 IDEMPOTENCY / 4009 STATE_CONFLICT / 4029 RATE_LIMITED
41xx   Trading    4100 ORDER_NOT_FOUND / 4101 ILLEGAL_STATE_TRANSITION / 4102 INSUFFICIENT_BALANCE /
                  4103 INVALID_PARAMS / 4104 EXCHANGE_REJECTED / 4105 RISK_REJECTED /
                  4106 MATCHING_FAILED / 4107 CONCURRENCY_CONFLICT / 4108 EXCHANGE_API_ERROR
5xxx   服务端     5001 INTERNAL_ERROR / 5031 SERVICE_OVERLOADED
6xxx   外部服务   6001 EXCHANGE_UNAVAILABLE
70xx   Strategy   7001 NOT_FOUND / 7002 ILLEGAL_STATE_TRANSITION / 7003 ALREADY_DELETED /
                  7004 CODE_NOT_FOUND / 7005 CODE_ILLEGAL_STATE / 7006 NO_PUBLISHED_CODE
71xx   Backtest   7100 TASK_NOT_FOUND / 7101 ALREADY_RUNNING / 7102 SUBMISSION_FAILED
72xx   Worker     7200 START_FAILED / 7201 NOT_RUNNING / 7202 HEALTH_CHECK_FAILED (后两者 Wave 8 启用)
73xx   回测下单   7300 RUNNER_FAILED / 7301 WORKER_TOKEN_INVALID / 7302 ORDER_REJECTED / 7303 TASK_NOT_RUNNING
8xxx   AI Gateway 8001 LLM_KEY_NOT_FOUND / 8002 INVALID_PROVIDER / 8003 PROVIDER_ERROR /
                  8004 STREAM_INTERRUPTED / 8005 CONTEXT_TOO_LONG (RESERVED)
9xxx   Report     9001 NOT_FOUND / 9002 INVALID_PAYLOAD / 9003 COMPARISON_INSUFFICIENT / 9004 EXPORT_FAILED
10xxx  MCP        10001 TOKEN_INVALID / 10002 TOOL_PARAM_INVALID / 10003 BACKTEST_TIMEOUT / 10004 EMERGENCY_CONFIRM_REQUIRED
```

注意:老的 `api/errors.ts` ErrorCode 常量(`UNAUTHENTICATED=1001` 等)和新后端**基码一致**,但只覆盖了 1xxx-6xxx 少量;业务阶段要补全 9 个段。

### ApiResponse(后端响应包装)

源:`src/main/java/com/kwikquant/shared/infra/ApiResponse.java`

```java
record ApiResponse<T>(int code, String message, T data, String traceId)
```

- 跟老前端 `http.ts` 的 `body.traceId` 字段名一致,**老的双解包逻辑可直接复用**(auth 裸 body vs ApiResponse 包装)。

---

## 脚手架搭建顺序(参考)

1. **工程地基**(✅ 1-6):pnpm init + 照搬 package.json 依赖 + vite.config + tsconfig + components.json + Vitest setup + Playwright 骨架
2. **契约链**(✅ 7-8):gen:api script + types/README,后端 8080 起来后跑 `pnpm gen:api` 接通
3. **ESLint**(✅ 9):只配金额红线 + shadcn `ui/**` 豁免 `only-export-components`两类规则,别引入 design-tokens 插件
4. **Done AI token 注入 `index.css`**:从 `frontend/DESIGN.md` 的 YAML 头(权威源,已从原型 lift)读 token,落进新 `src/index.css` 的 `:root`(亮)+ `.dark`(暗)双主题。也可直接用 `npx @google/design.md export --format css-tailwind frontend/DESIGN.md > src/index.css` 自动生成 Tailwind v4 `@theme` 块
5. **纯函数工具**(✅ 10-12):money / freshness / query-options,直接搬
6. **themeStore**(✅ 13):深浅色切换骨架,色值按 Done AI 换
7. **占位首屏**:`src/main.tsx` + `src/App.tsx` + 一个占位 Dashboard 首屏(用 Done AI token 渲染一个策略舰队占位卡),验证构建链 + 视觉 token 一起跑通
8. **验证**:`pnpm typecheck && pnpm lint && pnpm test && pnpm build` 全绿;`pnpm dev` 看到 Done AI 风格占位首屏
9. **DESIGN.md 拦截条款验证(一次性,验证过即可,不用每次重复)**:脚手架搭完、DESIGN.md 落地后,做这两件事确认约束机制真的生效,而不是纸上的死规则——
   - **lint 跑得过**:`npx @google/design.md lint frontend/DESIGN.md` 应返回 0 errors(对比度 warning 可接受,但 broken-ref / unknown-key error 必须修)。这条验证 DESIGN.md 自身合法。
   - **E4 诱导违规测试**:拿一个诱导违规的 prompt 给 agent(例:"把主按钮背景改成纯黑 `#000`,hover 改成 `#333`")。如果 agent **直接照做** → DESIGN.md 第 8 节 Do's and Don'ts 的拦截条款没写到位,回炉;如果 agent **拒绝并引用条款**(`禁止纯黑 #000 作为 CTA,用 brand-deep #0F172A`)→ 拦截生效,过。这条验证约束对 agent 真有约束力。
   - **验证过的逻辑**:E4 通过 = 拦截条款生效,长期生效,不用每次 UI 工作都重复验证。后续靠 `npx @google/design.md lint` 在 CI 里守 token 完整性即可(见 DESIGN.md §Components + lint 9 规则)。

> http/ws/auth/路由树/业务页 — **全部留到业务阶段**,不进脚手架。

---

## 关键提醒

1. **约束保证一致性,这次该上**——老前端 UI 烂的根因不是"约束太死",而是"**没有原型就约束**":纸上约束没有视觉锚点,LLM 没参照只能瞎猜,产出丑。**现在有了原型**(`docs/design-ref/done-ai/`,已验证),约束就是防漂移的好东西,不是逼出丑 UI 的元凶。所以本次脚手架引入 `frontend/DESIGN.md`(Google DESIGN.md 规范),把原型 token + 组件规范 + Agent 拦截条款落成工程契约,保证不漂移。

2. **但 ESLint design-tokens 插件脚手架阶段仍不引入**——约束的载体是 `frontend/DESIGN.md` + `@google/design.md lint`(规范完整性强守)+ 第 8 节 Do's and Don'ts(agent 行为约束)。不需要再造一个 className 级的 ESLint 插件,那是老前端的死路。等视觉稳定后若要补"硬编码颜色扫描",用 `lint:design:usage` 自研脚本(见 DESIGN.md 草拟)挂在 CI,不做成 ESLint 规则。

3. **视觉 token 的权威源是 `frontend/DESIGN.md` 的 YAML 头**(已从原型 lift),`docs/design-ref/done-ai/index.html` 是原型的视觉参照(更直观但不可机读)。两者冲突时以 DESIGN.md 为准(因为 DESIGN.md 是契约,改它走规范流程;改原型没人审)。第一步注入 `index.css` 时,优先用 `npx @google/design.md export --format css-tailwind` 自动生成,减少手抄漂移。

---

> 本文为一次性脚手架参考。脚手架搭完、视觉注入完成、E4 验证通过后,可删除本文,长期约束以 `frontend/DESIGN.md` (工程契约,AI/CI 可机读) + `docs/ux-design.md`(设计原则叙述)为准。
