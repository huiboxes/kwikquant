# 前端 mock 占位审计

> 2026-07-18 二次核实 `src/types/api-gen.ts` 后纠正(**上轮 /loop 审计把若干 A 类误判为 B 类,已修正**)。
>
> **分类规则**:
> - **A 类** = 后端端点/字段已就绪,前端 TDD 直接接(本审计负责接入)。
> - **架构师拍板·A 路** = 有架构选择空间的项,选定 A 路方案 + tradeoff(用户已 ACK)。
> - **给后端的建议** = 前端窗口不执行,留给后端窗口改契约/补端点。
> - **删占位** = 不做该功能,删 mock UI,不留假按钮。
> - **必须用户拍板** = 产品/业务决策,不是架构选择。
> - **C 类** = 非 mock(纯前端重构/映射,不影响接入)。

## ⚠️ 编号空间说明

前端代码注释里的 `TD-xxx` **与 `docs/tech-debt.md`(后端债)编号空间独立、有重叠但语义不同**。例:前端 `TD-008` 指"批量 ticker 端点缺失",后端 `tech-debt.md` 的 `TD-008` 指 `MatchingKernel.java` 流动性回退。前端 TD 只在代码注释里,**未集中登记进 tech-debt.md**。本审计是前端 TD 的首次集中清单。

---

## A 类·已接入(历史)

| TD | 现状 | 状态 |
|---|---|---|
| **TD-021** 导入外部报告 | `POST /reports/import` + `importReport` api + `useImportReport` hook | ✅ `parseImportReport` 纯函数+9 单测 + 组件接文件选择+2 组件测 |
| **TD-046** trading WS 推送 | `useTradingEvents` 已订阅 orders/fills/positions/portfolio 四主题 | ✅ 核实已接,修注释 |
| **trade-history 导出** | `GET /api/v1/trade-history/export`(csv/json) | ✅ `authFetch` + `parseContentDispositionFilename`(6 测)+ Blob 下载 + 3 单测 |

---

## A 类·待接(本轮纠偏:上轮误判 B,核实契约已就绪)

> 上轮 /loop 审计时未逐字段核 `api-gen.ts`,把以下项判为"B 需后端补"。二次核实后发现后端端点/字段**早已在契约里**,纯属前端漏接 —— 纯前端 TDD 可直接接,**不碰后端、不动契约**。

| TD | 契约现状(已核实) | 接入方式 |
|---|---|---|
| **TD-003** portfolio | `GET /portfolio/equity-curve` + `/portfolio/summary` + `/portfolio/pnl` 三端点 | Dashboard/Portfolio 用 equity-curve 画图,TopBar/summary 用 summary/pnl |
| **TD-009 / TD-047** orderbook | `GET /market/orderbook/{exchange}/{marketType}/{symbol}` | TradingPage OrderBook 区替换硬编码派生 mock |
| **TD-040** uPnl/currentPrice | `PositionDto.unrealizedPnl` + `currentPrice`(行情不可用为 null) | PositionRow 用真实字段,删占位 — |
| **TD-044** 平仓 | `POST /positions/{positionId}/close` | 平仓按钮替换反向市价单占位 toast |
| **TD-045** reset PAPER | `POST /accounts/{id}/paper/reset` | 重置按钮替换占位 toast,加 ConfirmDialog |
| **TD-019** 逐笔 pnl/equity | `TradeRecordDto.realizedPnl` + `equity`(无配对/无数据为 null) | BacktestPanel trades 表用真实字段,删占位 — |
| **TD-023** avgTradeDuration | `BacktestReportDetailDto.metrics.avgTradeDurationSeconds`(MetricsDto) | BacktestPanel detail 用真实字段(列表 DTO 仍无此字段,见 TD-022) |
| **TD-008** 批量 ticker(半 A) | `/market/pairs`(symbols 发现)+ `/market/ticker/{exchange}/{marketType}/{symbol}`(单点) | 批量端点仍缺;用 pairs 发现 symbols + 循环单 ticker 兜底,标性能债。后端补批量端点是优化项不阻塞 |

---

## 架构师拍板·A 路(6 项,用户已 ACK)

> 这些项有架构选择空间(前端算/聚合 vs 后端补)。选定 A 路 = 前端方案,后端不必改。tradeoff 摘要如下,完整 rationale 见会话。

| TD | 决策(A 路) | tradeoff |
|---|---|---|
| **TD-018** 对比叠加 equityCurve | `ComparisonResultDto.reports` 是 list DTO(无 curve)→ 前端拉两份 `/reports/{id}` detail,各取 equityCurve 叠图 | 多 2 请求 vs 契约膨胀;对比是低频操作,接受多请求换契约零侵入 |
| **TD-022** 列表 sparkline | list DTO 无 equityCurve → 列表**不画 sparkline**,改用 `totalReturn` 着色小条(已有字段) | 列表轻量加载 vs 视觉信息密度;要保 sparkline 走后端补 20 点摘要(留 B 备选) |
| **TD-036** running PnL | 无独立端点 → 前端聚合 positions `sum(unrealizedPnl + realizedPnl)`,WS 推动实时跳动 | 实时性好;**口径必须与 `/portfolio/pnl` 对齐**,否则两个数字打架 |
| **TD-010** Heatmap 多周期 percentage | 无多周期 ticker 字段 → 前端用 `/market/klines` 本地算各周期涨跌(decimal.js 守精度) | K 线端点已就绪;Heatmap 冷启动 N×M 个 kline 请求,需懒加载/缓存 |
| **TD-033** resume 语义 | `PAUSED→RUNNING` 复用 `/start`(start 幂等:非 STOPPED 态都可 start),不单独加 resume 端点 | 契约注释澄清 start 幂等范围;**若后端 start 对 PAUSED 态拒绝则降级为后端改(动手时核状态机)** |
| **TD-039** 运行配置(降级 A) | 改 symbol/interval → 提醒"将创建新策略" → ConfirmDialog → `createStrategy` fork(继承 code + 新名可编辑) | **后端不用补运行配置端点**(`POST /strategies` 已有);fork 须继承 code 不建空策略;**与 StrategyPage 重构 plan(`snuggly-noodling-otter.md`)的 BottomControlBar 交集,一起做**;动手核 createStrategy 是否带 code |

---

## 给后端的建议(前端窗口不执行)

> 前端窗口不碰后端 Java。这些建议留给后端窗口改契约/补字段,前端随后消费新 `api-gen.ts`。

| TD | 建议 | tradeoff / 备注 |
|---|---|---|
| **TD-012** PAPER 行情来源 | PAPER 复用 LIVE 同源 CCXT ticker(共享行情),只在撮合层 PaperExecutor 分叉 | 与 LIVE 行情一致(符合"PAPER=用真实行情做纸面撮合"产品语义) vs 独立模拟行情(可注入假设场景但偏离语义)。强推荐同源 |
| **TD-041** reports strategyId | `BacktestReportDto` / `BacktestReportDetailDto` 补 `strategyId: number \| null` + `strategyName: string \| null`(快照) | strategyName 快照在策略改名后会"过期",但这是快照语义想要的(历史报告保留当时的名),非缺陷;避免列表 N+1 查策略名。IMPORT/USER 无归属 → null。**不补 strategyVersion/codeId**(code 版本是 VersionsDialog 独立链路,塞进 report 是职责越界)** |

---

## 删占位·不做(不留假按钮)

> 用户决策:主要功能还没调通,这些次要功能不做。**不做 = 删 mock UI/占位按钮**,假按钮比没按钮更糟(诱导用户点无效操作)。

| TD | 处置 |
|---|---|
| **TD-026** 会话管理 | 删会话卡(静态占位) |
| **TD-027** 轮换 LLM key | 删轮换按钮(占位 toast) |
| **TD-024** LLM key active 徽章 | 不展启用徽章(不补 `active` 字段) |
| **TD-025** MCP token scopes | 后端不支持 scopes 就删 scopes UI,不传后端 |
| **TD-028** telegram/webhook 渠道 | 后端不支持就删渠道选项,不硬编码 mock |

---

## 必须用户拍板(产品/业务,待定)

| 项 | 决策点 |
|---|---|
| **OAuth** | 要不要做社交登录(GitHub/Google 等)。LoginPage 社交按钮当前视觉占位 |
| **TD-042** marketType | SPOT/PERP 产品支持范围。当前前端固定 SPOT,参数产品无意义 |

---

## C 类:非 mock(纯前端重构/映射,不影响接入)

| TD | 说明 |
|---|---|
| TD-029 | provider 枚举 → 中文 label 映射(契约是枚举,原型是中文),纯前端映射,非 mock |
| TD-030 | auth.ts api 模块只含 changePassword,login/register/refresh 在 hooks 裸调 —— 重构统一,非 mock |
| TD-031 | `McpTokenView` 不含明文 token,移除 show/hide toggle —— 纯前端调整 |

---

## 执行顺序(当前轮)

1. ✅ 修审计文档(本文件)
2. ⏳ 接误判 A 类(纯前端 TDD):TD-003 / 009·047 / 040 / 044 / 045 / 019 / 023 / 008
3. ⏳ 6 架构决策 A 路(TDD):TD-018 / 022 / 036 / 010 / 033
4. ⏳ TD-039 fork 交互(与 StrategyPage 重构交集)
5. ⏳ Settings 删占位 UI:024 / 025 / 026 / 027 / 028
6. ➡️ TD-012 / TD-041 给后端(前端窗口不执行)

**金额红线**:所有 A 类接入涉及金额字段(uPnl/realizedPnl/equity/equityCurve 点)一律 `decimal.js`(`toDecimal` + `formatMoney`),`parseFloat`/`Number` 参与运算被 ESLint 硬拦。展示用 `font-mono` + `tnum`。
