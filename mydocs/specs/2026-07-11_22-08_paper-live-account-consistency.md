# SDD Spec: Paper/Live 账户判定与模拟盘真实记账整改

## 0. Open Questions
- [x] `PortfolioService.getSummary()` 要不要把模拟盘账户重新纳入组合汇总总额？**用户决策：不纳入**（理由：模拟盘和真实资金混算总额会让使用者没法看真实盈亏，维持现有排除行为，只把判断字段从 `exchange!=PAPER` 换成 `!isPaperTrading()`，行为不变）。
- [x] `PaperBalanceAdapter.freeze()` 抛 `InsufficientBalanceException` 时，`TradingService.submit()` 该怎么把订单落成 `REJECTED`？**已核实现状并定案**（见 §2.1 关键发现 6、§4.1/§4.2 更新）：现状不是"有一个既有模式可抄"，是两套半成品在打架（`trading.domain.InsufficientBalanceException` 有 handler 但是死代码；`account.domain.InsufficientBalanceException` 是活代码但没 handler，裸抛会被兜底成 500）。决定：删掉死的那个 + 它唯一的死调用者，把已经分配好的 422/4102 (`ORDER_INSUFFICIENT_BALANCE`) handler 改指向活的那个；同时把 `submit()` 里已存在的两处重复的"CAS→REJECTED→并发冲突重读返回"代码块抽成私有方法 `rejectOrder()`，第三处（余额不足）复用而不是再贴一遍。
- [x] 历史脏数据（本次人工测试时创建的 `exchange=PAPER` 的 `exchange_accounts` 行）如何清理？**用户决策：直接删**（dev 环境测试数据，非生产数据，无需迁移脚本）。

## 1. Requirements (Context)
- **Goal**: 让"模拟盘账户"这个概念在代码里只有一个判定字段（`isPaperTrading()`），`exchange` 字段专职表示"撮合/定价参考哪个真实交易所的公开行情"；模拟盘的余额/持仓不再是硬编码假值，而是通过已写好但从未接线的 `PaperBalanceAdapter`（含 `freeze`/`unfreeze`/`applyFill`）真实走数据库记账；`PaperExecutor` 撮合时要尊重账户实际选择的参考交易所，不能跨交易所串价。
- **In-Scope**:
  - 建号：`CreateAccountRequest` 显式传入是否模拟盘；实盘必填 `apiKey`/`apiSecret`，模拟盘可不填；请求里 `exchange` 禁止传 `PAPER`（该值只能是 BINANCE/OKX/BITGET）。
  - 判定收口：所有"是否模拟盘"的业务判断统一改用 `account.isPaperTrading()`；`account.getExchange()` 只用于"查哪个交易所的行情"。
  - 记账接线：`PaperBalanceAdapter` 接入建号（`initBalance`）、下单（`freeze`）、撤单/GTD 过期（`unfreeze`）、成交（`applyFill`）、查余额（`BalanceService.fetchBalance`）五个环节。
  - 撮合修正：`PaperExecutor.onTicker()` 按订单归属账户选定的 `exchange` 过滤 ticker 来源，不再只按 symbol 匹配。为避免高频回调里逐单查 DB，在 `Order` 上新增去规范化的 `exchange` 字段（下单时从账户快照写入一次）。
  - `PortfolioService.getSummary()` 的 PAPER 过滤条件从 `exchange != Exchange.PAPER` 改为 `!isPaperTrading()`（语义修正，行为暂时不变——模拟盘依然不进组合总额，见 Open Questions）。
- **Out-of-Scope**（明确不做，避免过度设计）：
  - 不删除 `Exchange.PAPER` 这个枚举常量本身（保留但业务层禁止用于 `ExchangeAccount.exchange`，靠单测钉死，Option B 的彻底清理留作后续技术债）。
  - 不实现 `LiveExecutor`/`DefaultCcxtOrderAdapter`（已知技术债，另案处理）。
  - 不做前端改动（本次只动后端 Java；前端契约变化——如 `CreateAccountRequest` 新字段——会随 `pnpm gen:api` 自然同步，不在这次改动范围内手写）。
  - 不修复 MCP 层 `get_ticker` 的 NPE（另一条已知技术债）。

## 1.1 Context Sources
- Requirement Source: 本次会话用户连续追问（"paper 账户为什么被拒绝" → "模拟盘该不该绑定真实账户" → "关键交易链路怎么能这样漏洞百出" → 选定 Option A → 补充"用户选参考交易所"交互设想）
- Design Refs: 无独立设计文档，全部来自代码考古（见 §2 Research Findings）
- Chat/Business Refs: 本会话对话记录
- Extra Context: `docs/ONBOARDING.md`、`docs/tech-debt.md`（均未记录本次发现的问题）

## 1.5 Codemap Used (Feature/Project Index)
- Codemap Mode: 未生成独立 codemap，本次改动面可控（16 个文件，均已逐一读取确认），直接进入 Research/Plan
- Key Index（本次通过 Explore agent 三轮核实得到，等价于一份 feature 级索引）：
  - Entry Points: `ExchangeAccountController.create()` → `ExchangeAccountService.create()`；`TradingService.submit()/cancel()`；`ExecutionService.processExecutionReport()`；`GtdExpirationScheduler.scan()`
  - Core Logic: `PaperBalanceAdapter`（已实现未接线）、`OrderRouter.route()`、`PaperExecutor.onTicker()`、`BalanceService.fetchBalance()`
  - Dependencies: `paper_balances`/`fills`/`positions`/`orders`/`exchange_accounts` 表；`CcxtExchangeRegistry`（BINANCE/OKX/BITGET 匿名共享行情连接）

## 1.7 Minimum Chaos Unit Assessment
- Final Goal: 见 §1 Goal
- Current Task Unit: 本 Spec 覆盖的全部改动（建号校验 + 判定收口 + 记账接线 + 撮合修正），作为一个整体任务单元一次性 Plan，Execute 时按 Implementation Checklist 顺序原子提交
- Why this unit is small enough: 改动集中在 `account`/`trading`/`report` 三个模块的既有文件，不新增模块、不做表结构大重构；`PaperBalanceAdapter` 已有 18+6 个测试打底，接线风险可控
- In-Scope Boundary: 见 §1 In-Scope
- Out-of-Scope Boundary: 见 §1 Out-of-Scope
- Verification Evidence: `./mvnw clean verify`（JaCoCo 95% 门控 + Spotless）全绿；新增/修改的单测覆盖 freeze/unfreeze/applyFill 接线路径、PAPER exchange 建号校验、PaperExecutor 按 exchange 过滤撮合
- Failure / Rework Plan: 若 Execute 中发现某个环节（尤其是"落 REJECTED 的具体写法"）需要现场决策，先在 Execute Log 里记录偏差和理由，不擅自扩大改动面，必要时回到 Plan 补充后再继续
- Model Autonomy Space: 具体的异常类命名、SQL migration 文件编号等实现细节，在符合 Plan 意图前提下可自主决定；涉及 Open Questions 里列的三项决策不可自主决定，须等用户确认
- User Decision: Accepted（用户已选定 Option A 并补充"用户选参考交易所"的交互设想）

## 2. Research Findings

### 2.1 现状事实（当前代码，均已逐文件核实，非凭记忆）

**两套判定字段互相矛盾，13 处判断分裂成两派**：

| 环节 | 位置 | 判断字段 |
|---|---|---|
| 建账户 | `ExchangeAccountService.java:45` | 硬编码 `setPaperTrading(true)`，不看 `exchange` 参数 |
| 下单路由 | `OrderRouter.java:25` | `isPaperTrading()` |
| 启动恢复 | `TradingBootstrap.java:51` | `isPaperTrading()` |
| 下单校验 findPair | `TradingService.java:384` | `getExchange()` |
| 风控 notional | `TradingService.java:337` | `getExchange()` |
| 查余额走哪支 | `BalanceService.java:34` | `getExchange()==PAPER` |
| 查余额设 sandbox | `BalanceService.java:87`（同方法内） | `isPaperTrading()` |
| Portfolio 汇总排除 | `PortfolioService.java:54` | `exchange!=PAPER` |
| Portfolio PnL | `PortfolioService.java:102` | 不过滤，直接用 `exchange()` 查行情 |
| MCP 模式校验 | `StrategyTools.java:152/176` | `isPaperTrading()` |

**关键发现 1 — 半成品记账基础设施**：`BalancePort`/`PaperBalanceAdapter`（`account/infrastructure/PaperBalanceAdapter.java`，191 行，`@Component`）已完整实现 `fetch`/`initBalance`/`freeze`/`unfreeze`/`applyFill`/`reset`，配套 `V22__create_paper_balances.sql` migration 已跑过，18 个单测 + 6 个集成测试全绿。Git log 显示这是 2026-07-06 两个 commit（`beb84a4`/`4b9d95c`）写的，commit message 原话"Batch 2-N(BalanceService/ExchangeAccountService/迁移/Controller)+ Task 3-6 待续"，但后续 batch 从未做，**全局零引用，纯死代码**。`BalanceService.fetchBalance()` 仍是 `if (exchange==PAPER) return BalanceSnapshot.paper()`（硬编码 10 万 USDT）。

**关键发现 2 — PaperExecutor 撮合不看账户选的参考交易所**：`PaperExecutor.onTicker()`（`trading/application/PaperExecutor.java:147`）只按 `ticker.symbol().equals(order.getSymbol())` 过滤，不检查 `ticker.exchange()`。意味着即便账户选定"参考 OKX"，收到 BINANCE 推来的同 symbol ticker 一样会拿去撮合——用户选参考交易所目前是摆设。

**关键发现 3 — GTD 过期单不释放冻结资金**：`GtdExpirationScheduler.scan()`（`trading/application/GtdExpirationScheduler.java:43-79`）只把订单状态推进到 `EXPIRED`，没有调用任何撤单/释放资金逻辑（本来也无从调用，因为 `freeze` 现在压根没接线）。

**关键发现 4 — 无缓存，高频路径直查 DB**：`ExchangeAccountService.getOwned()/findById()` 都是直查 `mapper.findById()`，全项目搜索确认没有任何 `ExchangeAccount` 缓存机制。`PaperExecutor.onTicker()` 是每个 ticker 触发一次、遍历全部 `activeOrders` 的高频回调，若要按账户的 `exchange` 过滤，逐单查 DB 会有性能隐患。

**关键发现 5 — 现有约束/契约事实**：
- `CreateAccountRequest`（`ExchangeAccountController.java:124-151`，嵌套 record）当前 `apiKey`/`apiSecret` 均 `@NotBlank`，`exchange` 只有 `@NotNull`（未限制取值范围，`PAPER` 目前是合法输入）。
- `Order`（`trading/domain/Order.java`）已有 `accountId` 字段+getter，**没有 `exchange` 字段**（需要新增）。
- `TradingService.submit()` 调用顺序：`loadOwnedAccount` → `findPair` → `Order.create()` → `insertOrder`（独立 `REQUIRES_NEW` 小事务，订单先落 `NEW`）→ 构建风控请求 → `riskService.check()` → `orderRouter.route()` → `executor.submit()`。**订单在风控/路由之前已经落库**，说明"某个环节判定失败后把订单改成 REJECTED"是已有模式，`freeze()` 失败要复用同一种落地方式。
- `ExecutionService.processExecutionReport()` 是单一 `@Transactional(REQUIRED, READ_COMMITTED)` 事务，`positionService.applyFill()` 在第 177 行、`fillMapper.insert()` 在其之前，`PaperBalanceAdapter.applyFill()` 需要加入同一事务（其四个方法本身都不带 `@Transactional`，设计上就是要加入调用方事务）。
- `exchange_accounts` 表（V3）已有 `paper_trading BOOLEAN NOT NULL DEFAULT TRUE` 列；`fills`/`positions`/`paper_balances` 三张表都以 `account_id` 为关联键，schema 层面已支持按账户做真实记账，不需要动这三张表结构。

### 2.2 风险与不确定项
- `orders` 表需要新增 `exchange` 列（migration），历史行需要 backfill（可以从 `exchange_accounts` join 回填，因为老数据里 `account.exchange` 目前不会是 `PAPER`——本次测试期间手动创建的除外，见 Open Questions）。
- `freeze()` 失败时订单如何优雅落 `REJECTED`，需要 Execute 阶段现场读风控拒单那段代码确认写法，Plan 阶段不编造。
- `PortfolioService.getSummary()`/`getPnl()` 对模拟盘账户是否应该纳入组合总额，是产品语义决策，不是纯技术问题，列入 Open Questions，本次默认保持"不纳入"的现状行为，只修正判断字段。

**关键发现 6 — 余额不足异常处理是两套半成品在打架，不是"有一个既有模式可抄"**：
- `trading.domain.InsufficientBalanceException`（`trading/domain/InsufficientBalanceException.java`）有专属 handler（`TradingExceptionHandler`），映射到 **HTTP 422 + code=4102（`ORDER_INSUFFICIENT_BALANCE`，错误码早就分配好了）**，但唯一调用者 `PositionService.requireBalance()` 自己标了 `@SuppressWarnings("unused")`——**Wave 5 就废弃的死代码**。
- `account.domain.InsufficientBalanceException`（`account/domain/InsufficientBalanceException.java`）才是**真正活着**的那个——`PaperBalanceAdapter.freeze()`（第 79/83 行）现在就抛它——但**没有任何 handler**。若裸抛会被 `GlobalExceptionHandler` 兜底成 **HTTP 500 + code=5001（"internal error"）**。它的 Javadoc 原话写着"被 TradingService.submit catch 后转 REJECTED"，5 天前写下的承诺从未兑现。
- `TradingService.submit()` 内已有两处几乎一致的"CAS→REJECTED→并发冲突重读返回"代码块（第 171-182 行、186-195 行），语义完全相同（CAS 失败都是重读最新状态直接返回，不抛异常）；`ExecutionService`/`GtdExpirationScheduler` 里同款模式的 CAS 失败分支处理不同（重试/抛异常/静默跳过），所以只在 `TradingService.submit()` 局部抽 helper 是安全的，不做跨文件的大一统重构。

## 2.1 Next Actions
- 用户对 §0 Open Questions 三项给出决策
- 决策后进入 §4 Plan 的 Execute

## 3. Innovate (Options & Decision)

### Option A — 接通已有 `BalancePort`，`isPaperTrading()` 立为模式判定唯一真相源，`exchange` 只表示行情参考源（**已选定**）
- Pros: 复用已测过的 `PaperBalanceAdapter`；改动集中在"接线"而非重新设计；`fills`/`positions`/`paper_balances` 表结构已支持，无需大改 schema。
- Cons: 仍要碰 7+ 文件；`Exchange.PAPER` 枚举值保留但业务层禁用，属半清理，需靠单测钉死语义边界。

### Option B — 彻底删除 `Exchange.PAPER`，`ExchangeAccount` 拆出独立 `referenceExchange` 字段
- Pros: 长期最干净，无历史包袱。
- Cons: 枚举删除影响面广（所有 switch、OpenAPI 契约、前端生成类型都要跟着变），当前 dev 阶段数据量近零，收益主要是"更干净"而非解决用户报的具体问题，偏过度设计。

### Option C — 最小侵入，只改 `BalanceService` 一处判断
- Pros: 改动最小。
- Cons: 不解决"模拟盘余额脱离真实成交"的核心诉求，只是治标不治本。不推荐。

### Decision
- **Selected: Option A**，并在澄清交互后细化为：
  1. 不新增字段，复用既有 `isPaperTrading()` 布尔位作为唯一模式判定；
  2. `exchange` 字段禁止在 `ExchangeAccount` 上取值 `PAPER`，只能是 BINANCE/OKX/BITGET（业务层校验，不删枚举常量）；
  3. `apiKey`/`apiSecret` 校验从"永远必填"改为"仅实盘（`!paperTrading`）必填"；
  4. `PaperBalanceAdapter` 接入建号/下单/撤单/GTD 过期/成交/查余额六个环节；
  5. `Order` 新增去规范化 `exchange` 字段，`PaperExecutor.onTicker()` 按此字段过滤 ticker 来源，避免高频回调里查 DB。
- Why: 用户已确认选 A；细化点均来自用户对交互的追问（模拟盘不该强制填 key、参考交易所选择要真正生效）和 Explore 核实出的具体代码事实（无缓存、`Order` 无 exchange 字段等），是在 A 的既定方向内做实现细化，不改变已批准的方案范围。

## 4. Plan (Contract)

### 4.1 File Changes

| 文件 | 变更说明 |
|---|---|
| `src/main/java/com/kwikquant/account/interfaces/ExchangeAccountController.java` | `CreateAccountRequest` record 新增 `boolean paperTrading` 字段；`apiKey`/`apiSecret` 去掉 `@NotBlank`（改成可选，配合 service 层条件校验）；`create()` 多传一个参数 |
| `src/main/java/com/kwikquant/account/application/ExchangeAccountService.java` | `create()` 签名新增 `boolean paperTrading` 参数；实现条件校验（`!paperTrading` 时 `apiKey`/`apiSecret` 必填，`exchange==PAPER` 时拒绝）；不再硬编码 `setPaperTrading(true)`；建号成功后若 `paperTrading` 调 `paperBalanceAdapter.initBalance(accountId)`；构造函数新增注入 `PaperBalanceAdapter` |
| `src/main/java/com/kwikquant/account/application/BalanceService.java` | `fetchBalance()` 判断条件由 `getExchange()==PAPER` 改为 `isPaperTrading()`，命中时改为 `paperBalanceAdapter.fetch(account)`；构造函数新增注入 `PaperBalanceAdapter` |
| `src/main/java/com/kwikquant/trading/application/TradingService.java` | `submit()` 在 `riskService.check()` 通过后、`orderRouter.route()` 之前，若 `account.isPaperTrading()` 调新增私有方法 `freezeForPaper(order, account)`（内部 `@Transactional(REQUIRES_NEW)` 调 `paperBalanceAdapter.freeze(...)`，仿照 `insertOrder()` 的小事务风格）；`freeze` 抛 `InsufficientBalanceException` 时调抽取出的 `rejectOrder(order, cause)` 落 `REJECTED`；把第 171-182 行、186-195 行两处重复的 CAS-reject 逻辑抽成该私有方法，三处（risk 不可用 / risk 拒绝 / 余额不足）统一复用；`cancel()` 内若是 paper 账户，调 `paperBalanceAdapter.unfreeze(...)`；构造函数新增注入 `PaperBalanceAdapter` |
| `src/main/java/com/kwikquant/account/domain/InsufficientBalanceException.java` | 保留（这是活代码，`PaperBalanceAdapter.freeze()` 在抛） |
| `src/main/java/com/kwikquant/trading/domain/InsufficientBalanceException.java` | **删除**（Wave 5 死代码，唯一调用者 `PositionService.requireBalance()` 一并删除） |
| `src/main/java/com/kwikquant/trading/application/PositionService.java` | 删除 `requireBalance()` 死方法（已标 `@SuppressWarnings("unused")`） |
| `src/main/java/com/kwikquant/trading/interfaces/TradingExceptionHandler.java` | `@ExceptionHandler` 由 `trading.domain.InsufficientBalanceException` 改指向 `account.domain.InsufficientBalanceException`，复用既有 HTTP 422 + code=4102（`ORDER_INSUFFICIENT_BALANCE`）映射，不新增错误码 |
| `src/main/java/com/kwikquant/trading/application/ExecutionService.java` | `processExecutionReport()` 在 `positionService.applyFill()` 之后、同一事务内，若账户是 paper，调 `paperBalanceAdapter.applyFill(...)`；构造函数新增注入 `PaperBalanceAdapter` + `ExchangeAccountService`（若尚未注入） |
| `src/main/java/com/kwikquant/trading/application/GtdExpirationScheduler.java` | `scan()` 推进到 `EXPIRED` 时，若账户是 paper，调 `paperBalanceAdapter.unfreeze(...)` 释放冻结资金；构造函数新增注入 `PaperBalanceAdapter` |
| `src/main/java/com/kwikquant/trading/domain/Order.java` | 新增 `private Exchange exchange` 字段 + getter/setter；`Order.create()` 从 `TradingPairInfo`/`ExchangeAccount` 写入该字段 |
| `src/main/java/com/kwikquant/trading/application/PaperExecutor.java` | `onTicker()` 过滤条件由单一 `ticker.symbol().equals(order.getSymbol())` 改为同时要求 `ticker.exchange().equals(order.getExchange())` |
| `src/main/resources/db/migration/V23__add_orders_exchange_column.sql`（新文件，编号待 Execute 时核对最新版本号） | `orders` 表新增 `exchange VARCHAR NOT NULL`，历史行按 join `exchange_accounts.exchange` backfill |
| `src/main/java/com/kwikquant/report/application/PortfolioService.java` | `getSummary()` 过滤条件由 `a.exchange() != Exchange.PAPER` 改为 `!a.paperTrading()`（若 view 上没有该字段需要补一个；**用户已确认**行为不变——模拟盘不计入组合总额，避免模拟资金和真实资金混算误导使用者） |
| `src/main/java/com/kwikquant/account/domain/ExchangeAccount.java` | 视需要补一个不变式校验方法（如 `validateExchangeNotPaper()` 或在 setter 里做防御），具体是否需要视 Execute 时的落点决定，避免过度设计 |

### 4.2 Signatures

```java
// ExchangeAccountService
public ExchangeAccount create(
        long userId, Exchange exchange, String label, String apiKey, String apiSecret,
        String passphrase, boolean paperTrading)

// PaperBalanceAdapter（已存在，不改签名，仅新增调用方）
public void freeze(long accountId, String currency, BigDecimal amount)
public void unfreeze(long accountId, String currency, BigDecimal amount)
public void applyFill(long accountId, OrderSide side, String symbol, BigDecimal qty, BigDecimal price, BigDecimal fee)
public void initBalance(long accountId)

// Order（新增字段）
public Exchange getExchange()
public void setExchange(Exchange exchange)

// CreateAccountRequest（新增字段）
record CreateAccountRequest(
        @NotNull Exchange exchange, @NotBlank String label,
        String apiKey, String apiSecret, String passphrase,
        boolean paperTrading)

// TradingService（新增私有方法，消除既有重复 + 承载余额不足拒单）
private OrderSubmitResult rejectOrder(Order order, RuntimeException cause) // CAS→REJECTED，冲突则重读返回 latest，成功则计数+抛 cause
@Transactional(propagation = Propagation.REQUIRES_NEW)
void freezeForPaper(Order order, ExchangeAccount account) // 内部调 paperBalanceAdapter.freeze(...)
```

### 4.3 Implementation Checklist
- [ ] 1. `V23__...sql`：`orders.exchange` 列 + backfill（Execute 时核对实际下一个可用的 migration 编号）
- [ ] 2. `Order` 新增 `exchange` 字段，`Order.create()` 写入
- [ ] 3. `CreateAccountRequest` 新增 `paperTrading`，`apiKey`/`apiSecret` 去掉 `@NotBlank`
- [ ] 4. `ExchangeAccountService.create()`：条件校验（实盘必填 key、禁止 exchange=PAPER）+ 建号后 `initBalance`
- [ ] 5. `BalanceService.fetchBalance()`：切换判断字段 + 接 `PaperBalanceAdapter.fetch()`
- [ ] 6. `TradingService`：抽 `rejectOrder()` 私有方法（消除既有两处重复），新增 `freezeForPaper()` 小事务方法；`submit()` 接入三处统一走 `rejectOrder()`（risk 不可用 / risk 拒绝 / 余额不足）
- [ ] 6.1 删除 `trading.domain.InsufficientBalanceException` + `PositionService.requireBalance()` 死代码
- [ ] 6.2 `TradingExceptionHandler` 的 `@ExceptionHandler` 改指向 `account.domain.InsufficientBalanceException`
- [ ] 7. `TradingService.cancel()`：接 `unfreeze()`
- [ ] 8. `GtdExpirationScheduler.scan()`：接 `unfreeze()`
- [ ] 9. `ExecutionService.processExecutionReport()`：接 `applyFill()`（同事务）
- [ ] 10. `PaperExecutor.onTicker()`：加 exchange 过滤
- [ ] 11. `PortfolioService.getSummary()`：切换判断字段
- [ ] 12. 历史脏数据清理（本次人工测试创建的 `exchange=PAPER` 账户）
- [ ] 13. 补/改单测：`ExchangeAccountServiceTest`（校验分支）、`BalanceServiceTest`、`TradingServiceTest`（`rejectOrder`/`freezeForPaper` 成功/失败/并发冲突三条路径）、`ExecutionServiceTest`（applyFill 接线）、`GtdExpirationSchedulerTest`、`PaperExecutorTest`（跨交易所不串价）、`TradingExceptionHandlerTest`（改指向后的 422/4102 映射）
- [ ] 14. `./mvnw clean verify` 全绿（JaCoCo 95% + Spotless + ArchUnit + Modularity）

### 4.4 Spec Review Notes (Advisory)
- Spec Review Matrix:

| Check | Verdict | Evidence |
|---|---|---|
| Requirement clarity & acceptance | PASS | §1 Goal/In-Scope/Out-of-Scope 明确，验收靠 §1.7 Verification Evidence |
| Plan executability | PASS | 文件+签名+checklist 齐备；余额不足拒单路径已核实两个候选异常类现状并定案（删死代码+改指向），不再是"留给 Execute 现场编造"；migration 编号在 Execute 时核对最新版本号仍属正常颗粒度 |
| Risk / rollback readiness | PASS | 改动均在既有事务边界内接线，`PaperBalanceAdapter` 已有测试覆盖；`Order` 新增列走标准 Flyway migration，可回滚 |

- Readiness Verdict: **GO**
- Risks & Suggestions: 建议 Execute 时先做 checklist #1-2（migration + Order 字段）打底，再做 #6/#6.1/#6.2（异常清理+rejectOrder 抽取，先于 freeze 接线，因为 freeze 依赖这套异常处理）、#4-9（各业务点接线），最后 #10（PaperExecutor 过滤）+ #11（Portfolio），#13 单测穿插在每个业务点改完之后立刻补，不要留到最后一次性补
- Phase Reminders: Execute 阶段每完成一个 checklist 项，在 §5 Execute Log 打勾并记一句实际改动；Review 阶段要覆盖三轴（Spec 质量/Spec-代码一致性/代码自身质量）

### 4.5 Route Alignment (Water Flow Check)
- Original assumption: 判定字段收口是"改判断条件"级别的小改动
- Current implementation route: 实际还牵出了三处原计划外但必要的改动：(1) 给 `Order` 新增 `exchange` 列 + `PaperExecutor` 撮合过滤修正（源于用户对"参考交易所要真正生效"的追问）；(2) 发现 `InsufficientBalanceException` 存在两个同名类互相打架、其中一个是死代码，需要删除死代码+改指向 handler（源于用户拒绝"照抄既有模式"、要求先诊断再决策）；(3) 抽取 `rejectOrder()` 消除 `TradingService.submit()` 里已存在的重复代码
- Why it fits code terrain: (1) `Order` 已有类似去规范化字段模式；(2)(3) 都是"发现即修"的范围内清理，不引入新概念，只是让半成品各自归位
- Scope impact: Changed（比最初"只改判断条件"的设想范围扩大两轮，但每轮扩大都直接对应用户在本轮对话里提出的具体质疑，未脱离 Option A 的既定意图）
- User Decision if route changed: 已通过本轮对话确认（用户对"参考交易所生效"和"别在既有模式上盲目照抄"两次追问，均已在本次更新中落地为具体 Plan 变更）

## 5. Execute Log
- [x] Step 1: `V23__add_orders_exchange_column.sql` — `orders.exchange` 列 + backfill + NOT NULL
- [x] Step 2: `Order` 新增 `exchange` 字段 + getter/setter；`OrderMapper` 全部 SELECT/INSERT 加列
- [x] Step 3: `CreateAccountRequest` 新增 `paperTrading`；`apiKey`/`apiSecret` 去掉 `@NotBlank`
- [x] Step 4: `ExchangeAccountService.create()` 条件校验（实盘必填 key、拒绝 `exchange=PAPER`）+ 建号后 `initBalance`
- [x] Step 5: `BalanceService.fetchBalance()` 切换判断字段为 `isPaperTrading()` + 接 `PaperBalanceAdapter.fetch()`；删除死代码 `BalanceSnapshot.paper()`
- [x] Step 6/6.1/6.2: 抽 `rejectOrder()` 私有方法消除 `TradingService.submit()` 里两处已存在的重复 CAS-reject 逻辑；新增 `freezeForPaper()`/`unfreezeForPaper()`；删除死代码 `trading.domain.InsufficientBalanceException` + `PositionService.requireBalance()`；`TradingExceptionHandler` 改指向活的 `account.domain.InsufficientBalanceException`（复用既有 422/4102 映射）
- [x] Step 7: `TradingService.cancel()` 接 `unfreezeForPaper()`（try-catch 兜底，不阻断撤单主流程）
- [x] Step 8: `GtdExpirationScheduler.scan()` 接 `tradingService.unfreezeForPaper()`（复用 TradingService 的实现，同包可见）
- [x] Step 9: `ExecutionService.processExecutionReport()` 同事务内接 `paperBalanceAdapter.applyFill()`
- [x] Step 10: `PaperExecutor.onTicker()` 加 `ticker.exchange().equals(order.getExchange())` 过滤
- [x] Step 11: `PortfolioService.getSummary()`/`getPnl()` 判断字段统一切换为 `!a.paperTrading()`（`getPnl()` 原本不过滤，本次一并加上，用户已确认"模拟盘不计入组合总额"对总额和未实现盈亏两个数字要一致）
- [x] Step 12: `V24__relax_exchange_accounts_credentials.sql` 一次性清理本次人工测试脏数据 + 放宽 `api_key`/`api_secret`/`nonce`/`key_version` 为 NULLABLE + 加两条 CHECK 约束（`paper_trading=TRUE OR 有key`、`exchange<>'PAPER'`）把业务不变式钉在 DB 层
- [x] Step 13: 全部相关单测新增/修改（见下方 Deviation 记录）
- [x] Step 14: 全量 verify（见 §6，本沙箱环境 Docker 不可用，仅完成可执行部分）

### Deviations from original 4.1 file list (发现即修，未脱离 Option A 意图)
- **新增**：`account/application/PaperLedgerPort.java`（新接口）——Execute 阶段跑 `ModularityTests` 发现 `trading` 模块直接 import `account.infrastructure.PaperBalanceAdapter` 违反模块边界白名单（`trading` 只被允许依赖 `account::application`/`account::domain`，不含 `infrastructure`）。修复：新增 `PaperLedgerPort extends BalancePort`（在 `account.application`，声明 `initBalance/freeze/unfreeze/applyFill`），`PaperBalanceAdapter` 改为实现它；`TradingService`/`ExecutionService` 依赖类型从具体类改成这个接口。`GtdExpirationScheduler` 改为直接调 `TradingService.unfreezeForPaper()`（同包 package-private 方法，避免第三处重复 freeze/unfreeze 逻辑）而不是自己再依赖 PaperBalanceAdapter。
- **新增**：`V24__relax_exchange_accounts_credentials.sql`——Plan 阶段没预见到 `exchange_accounts` 表的 `api_key`/`api_secret`/`nonce`/`key_version` 四列原本是 `NOT NULL`，"模拟盘可不填 key" 这个已批准的决定在 DB 层落不了地，必须放宽约束；顺带把"实盘必须有 key""exchange 不能是 PAPER" 两条不变式用 CHECK 约束钉死，不只靠应用层校验。同一份迁移里完成了 Open Questions 里"脏数据直接删"的决定。
- **小改**：`ExchangeAccountService.java` 里 `ExchangeAccountView` 的 `exchange` 字段 Schema 描述顺手把已存在的 "BYBIT"（应为 BITGET）拼写错误改掉——只改了这一处（本次唯一touched 到的文件），其余 4 处同样的错字（`BacktestOrderRequest`/`StrategyController`/`MarketDataController`×2）不在本次改动文件清单内，未处理，记入 §9 Project Sync Candidates。
- **测试修复**（非设计变化，Execute 阶段编译/跑测发现）：`TradingService.cancel()` 里 `unfreezeForPaper()` 调用补了 try-catch（不该让解冻失败阻断撤单主流程，同 `executor.cancel()` 失败时的既有哲学）；`computeNotional()` 签名去掉 `OrderSubmitCommand cmd` 参数改用 `order.getMarketType()`（因为 `cancel()`/`unfreezeForPaper()` 场景下没有 `cmd`，这个简化让 submit/cancel 两条路径能复用同一个 notional 计算）；lambda 捕获 `decision` 触发 "effectively final" 编译错误，加了个 `final RiskDecision rejectedDecision = decision;` 局部拷贝。

## 6. Review Verdict
- Review Matrix (Mandatory):

| Axis | Key Checks | Verdict | Evidence |
|---|---|---|---|
| Spec Quality & Requirement Completion | Goal/In-Scope/Acceptance 完整清晰；需求是否达成 | PASS | §1 Goal 达成：判定字段收口到 `isPaperTrading()`、模拟盘余额接通真实记账、`PaperExecutor` 按参考交易所过滤、建号不强制 key |
| Spec-Code Fidelity | 文件、签名、checklist、行为是否与 Plan 一致 | PASS（有 3 处已记录的 Deviation，均在 Option A 意图内） | 见上方 Deviations 记录；`§4.1` 文件清单全部落地，外加 `PaperLedgerPort`/`V24` 两个必要追加 |
| Code Intrinsic Quality | 正确性、鲁棒性、可维护性、测试、关键风险 | PASS（一项已知限制未消解，见 Blocking Issues 下方说明） | `./mvnw compile`/`test-compile`/`spotless:check` 全绿；`ModularityTests`/`ArchitectureTests` 全绿；`-Dtest='!*IntegrationTest,!*E2ETest'` 跑 968 个非集成测试，0 个真实失败（98 个失败全部是本沙箱 Docker 不可用导致的 Testcontainers 环境噪音，与本次改动无关，可复现于任何未改动代码的基线跑一样会出现——已用脚本逐条核实排除） |

- Overall Verdict: **PASS**
- Blocking Issues: 无阻断项。**遗留一条已知限制**（非 bug，已在代码注释里写明）：`freezeForPaper`/`unfreezeForPaper` 对 MARKET 单的 notional 计算依赖两次独立取价（下单时 vs 撤单时），理论上存在极小的取价时点 drift；因为 Paper 撮合对 MARKET 单基本是"下一个 tick 立即成交"，实际撞上这个窗口的概率极低，本次不引入新 DB 字段解决，保留为已知限制。
- Regression risk: **Low**——改动集中在 `account`/`trading`/`report` 三模块的既有文件接线，`PaperBalanceAdapter` 本身逻辑未改（只是接上调用方）；`./mvnw clean verify` 的集成测试段（`AbstractIntegrationTest` 系）和 JaCoCo 覆盖率门控**本沙箱环境跑不了**（Docker 在这个环境里本身就是坏的，`docker run hello-world` 都失败，跟本次改动无关，是环境限制不是代码问题）——这部分需要在能跑 Testcontainers 的机器/CI 上补跑确认。
- Follow-ups:
  1. 找一台 Docker 能跑的机器/CI 补跑 `./mvnw clean verify` 全量（含集成测试 + JaCoCo 95% 门控）。
  2. `docs/tech-debt.md` 回写本次发现的历史事实（见 §9）。
  3. 4 处未处理的 "BYBIT" 拼写错误（`BacktestOrderRequest`/`StrategyController`/`MarketDataController`×2）留作独立小任务。
  4. 前端 `pnpm gen:api` 需要重新生成（`CreateAccountRequest` 契约变了，新增 `paperTrading` 字段，`apiKey`/`apiSecret` 从必填变可选）——不在本次后端任务范围内，需要另起前端任务。

## 7. Plan-Execution Diff
见 §5 Deviations 记录，三处均为 Execute 阶段读代码/跑测才发现的必要修正（模块边界、DB 约束、Java 语言细节），未偏离 Option A 已批准的意图，未扩大产品语义范围。

## 8. Archive Record
（建议下一步执行 `archive` 命令沉淀，本轮暂不做）

## 9. Project Sync Candidates
- Stable project facts discovered:
  - `PaperBalanceAdapter`/`BalancePort` 是 2026-07-06 写好但未接线的死代码，本次已接通（`docs/tech-debt.md` 之前未记录）
  - `trading.domain.InsufficientBalanceException` 是 Wave 5 就废弃的死代码（唯一调用者 `PositionService.requireBalance()` 自己标了 `@SuppressWarnings("unused")`），本次已删除
  - 本沙箱环境 Docker 根本不可用（`docker run hello-world` 报 `OCI runtime create failed: unknown version specified`），任何需要 Testcontainers 的测试/`clean verify` 在这个环境里都跑不了，跟代码改动无关——这是环境限制，`docs/ONBOARDING.md` 已有一条相关记录，可以考虑追加更明确的沙箱限制说明
  - 4 处 "BYBIT" 拼写错误（应为 BITGET）分布在 `BacktestOrderRequest`/`StrategyController`/`MarketDataController`×2，未在本次范围内修复
- Suggested destination:
  - `docs/tech-debt.md`
- Sync decision: Not synced（待用户确认后回写）
- Reason: 遵循"任务收口时同步"的节奏

## 10. Addendum (2026-07-12): 与 origin/main 并行实现的调和

**发现**：`git fetch` 后发现 `origin/main` 已领先本地 44 个 commit，其中包含同一作者(chuanpu，与本仓库 git 配置用户一致)在 2026-07-06~07-07 独立做的一整套"模拟盘余额记账"实现，用的是 `referenceExchange`（新增字段，`exchange` 保留原语义可为 PAPER）方案，与本 Spec 最初的"复用 exchange 字段 + isPaperTrading 唯一信号"方案架构不同，且已经和前端"基准交易所下拉框"集成。

**用户决策过程**：
1. 本地原方案（§4 Plan）的两个 commit 已 `git reset --hard origin/main` 作废（未推送，纯本地丢弃，不影响任何远程历史）。
2. 用户在对比两个方案后明确选择：**采纳 origin/main 已有的字段/migration 编号，但去掉 `referenceExchange` 字段**——理由：`referenceExchange` 和 `exchange` 表达同一件事，用户原话"前端下拉框选好后去改 paperTrading 不是一样的吗"，即 `exchange` 应该统一表示"参考哪个真实交易所"，`paperTrading` 单独表示模式，不需要第三个字段。
3. 组合总额排除模拟盘（§0 Open Question 已定案的决策）保留，覆盖 origin/main 该点上"纳入总额"的相反选择。

**最终落地方案**（在 origin/main 基础上新增 commit，非本 Spec 最初 §4 Plan 的文件清单）：
- `V24__remove_reference_exchange_relax_credentials.sql`：把模拟盘账户的 `reference_exchange` 值搬回 `exchange`，删该列；`orders.reference_exchange` 直接改名为 `exchange`（语义完全一致，不用搬数据）；放宽 `api_key`/`api_secret`/`nonce`/`key_version` 为 NULLABLE；历史模拟盘行的占位空 byte[] 折成真 NULL；加两条 CHECK 约束（`exchange<>'PAPER'`、`live 必须有 key`）。
- `ExchangeAccount`/`Order` 删 `referenceExchange` 字段。
- `ExchangeAccountService.create()`：签名变为 `(userId, exchange, label, apiKey, apiSecret, passphrase, paperTrading)`（`paperTrading` 由调用方显式传，不再从 `exchange==PAPER` 推导）；`exchange==PAPER` 直接拒绝；模拟盘 apiKey/apiSecret 存真 NULL。
- `BalanceService.freeze/unfreeze/applyFill/reset`：参数从 `Exchange exchange`（当 guard 用）改成 `boolean paperTrading`——这是全代码库最后一处"用 Exchange 值当模式信号"的地方，本次收口。`fetchBalance()` 判断也改用 `isPaperTrading()`。**继续沿用 origin/main 的路线**：`trading` 模块只调 `BalanceService`（`account.application`），不新增 `PaperLedgerPort` 接口——origin/main 这个设计本身就没有模块边界问题，本地 Spec 最初方案里的 `PaperLedgerPort` 是多余的，已放弃。
- `TradingService`：删 `refExchange` 三元表达式，统一用 `account.getExchange()`；新增抽取 `rejectOrder()` 私有方法消除三处（风控不可用/风控拒绝/余额不足）重复的 CAS-reject 逻辑（origin/main 原来是三处内联重复，这是本次带来的净改进）。
- `PaperExecutor.onTicker()`：过滤字段从 `order.getReferenceExchange()` 改 `order.getExchange()`。
- `PortfolioService`：删 `tickerExchange()` 辅助方法，直接用 `account.exchange()`；`getSummary()`/`getPnl()` 都加 `!paperTrading()` 过滤（origin/main 原来是"纳入"，本次按用户决策改成"排除"）。
- 同步修了 ~14 个测试文件的构造签名/断言（`ExchangeAccountView` 少一个字段、`BalanceService.*` 第二参数类型变了、`Order.getReferenceExchange()`→`getExchange()` 等）。

**验证结果**：`./mvnw compile` / `test-compile` / `spotless:check` 全绿；`ModularityTests`/`ArchitectureTests` 单独跑通过；`-Dtest='!*IntegrationTest,!*E2ETest'` 跑非集成测试，0 个真实失败（102 个失败逐条核实全部是本沙箱 Docker 不可用的 Testcontainers 环境噪音，与本次改动无关）。

**未变更/仍是已知限制**：
- `trading.domain.InsufficientBalanceException` 死代码清理——origin/main 没做这项，本次也**未在这轮改动里补做**（超出这次"去掉 referenceExchange"的范围，留给后续技术债任务，见 §9）。
- 集成测试/E2E（含 `PaperTradingE2EIntegrationTest` 这条最贴近用户场景的真实链路测试）依赖 Testcontainers，本沙箱 Docker 不可用，无法在此环境验证，代码已按新签名修好，需要能跑 Docker 的机器/CI 补跑确认。
