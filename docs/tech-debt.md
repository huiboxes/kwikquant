# 技术债清单

> 已识别但暂不修复的问题。修复后删除或移至「已处理」。

---

## 待处理

### TD-001 — refresh_tokens 清理未调度

- **模块**:account
- **位置**:`src/main/java/com/kwikquant/account/infrastructure/RefreshTokenMapper.java:52`
- **问题**:`deleteExpiredAndRevoked()` 已实现但无调用方,撤销/过期的 refresh token 永不删除,表持续膨胀。

### TD-002 — Kline upsert 无法区分实时更新与历史修正

- **模块**:market
- **位置**:`KlineMapper.java` upsert SQL + `V4_1__create_klines.sql`
- **问题**:klines 表无 updated_at/version 字段,upsert 用 GREATEST(high)/LEAST(low) 聚合 + 直接覆盖 close/volume。当交易所修正历史 K 线数据时,无法与正常实时更新区分,可能导致修正值被旧值覆盖或聚合出错误结果。
- **影响**:主流交易对(BTC/USDT 等)修正概率极低;山寨币/新上线交易对风险不为零。
- **建议**:加 updated_at 字段,或在 upsert 中引入版本号;历史回填走独立路径而非复用 watchOHLCV 的 upsert。
- **优先级**:低(短期可接受,Wave4+ 处理)


### RiskGate policy只有三条
- 后续有时间时优化，多新增几条

### 撮合双模式可选
- 目前撮合为了保证三态一致所以在Java撮合，后续如果有参数寻优的需求则加上双模式可选的功能


### CCXT fetchOrderBook 超时：Wave 3 既有 + CCXT 默认 timeout 兜底，MINOR 不修
### MCP E2E 冒烟测试：留 Wave 验证阶段（需 Spring AI MCP client 模拟 Agent）

---

## 已处理

(修复后移至此处)
