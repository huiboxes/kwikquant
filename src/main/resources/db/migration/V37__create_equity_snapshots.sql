-- 权益曲线定时快照表:PortfolioService.snapshotEquity @Scheduled 定时采集
-- (equity = 各账户 USDT total 之和 + 未实现 PnL),getEquityCurve 查历史返多点;
-- 无快照时 getEquityCurve 兜底返当前单点(前端 EquityCurveChart data.length<2 显"暂无数据")。
CREATE TABLE equity_snapshots (
    id             BIGSERIAL    PRIMARY KEY,
    user_id        BIGINT       NOT NULL,
    -- PAPER / LIVE / ALL(对齐 PortfolioService.filterByMode 语义;mode=null 历史向后兼容存 ALL)
    account_mode   VARCHAR(16)  NOT NULL,
    equity         NUMERIC(20,8) NOT NULL,
    snapshot_time  TIMESTAMPTZ  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- 查询:某用户某 mode 近 N 天的历史,按时间升序(前端曲线从左到右)。
CREATE INDEX idx_equity_snapshots_user_mode_time
    ON equity_snapshots(user_id, account_mode, snapshot_time DESC);
