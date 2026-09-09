-- 资金费率期次序列(仅 PERP,canonical symbol)。funding_time 是交易所原生结算时刻(期次键),
-- 不用本地墙钟:模拟盘结算幂等与回测资金费回放都按此网格对齐。
-- 不建任何二级索引:PK 前缀 (exchange, symbol) + btree 反向扫描已覆盖全部查询形态。
CREATE TABLE funding_rates (
    exchange         VARCHAR(20)    NOT NULL,
    symbol           VARCHAR(30)    NOT NULL,
    funding_time     TIMESTAMPTZ    NOT NULL,
    settled_rate     NUMERIC(20, 10),
    predicted_rate   NUMERIC(20, 10),
    interval_seconds INT,
    mark_price       NUMERIC(20, 8),
    source           VARCHAR(16)    NOT NULL DEFAULT 'EXCHANGE',
    received_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    PRIMARY KEY (exchange, symbol, funding_time)
);

COMMENT ON TABLE funding_rates IS
    '资金费率期次序列(正=多头付空头)。OKX 历史窗口仅约 94 天,窗口外由长历史所(Binance)代理,source 标记。';
COMMENT ON COLUMN funding_rates.funding_time IS '期次键=交易所原生结算时刻(UTC 网格,如 00/08/16),非本地墙钟';
COMMENT ON COLUMN funding_rates.settled_rate IS '交易所已结算值;一旦写入不被 predicted-only 行覆盖';
COMMENT ON COLUMN funding_rates.predicted_rate IS '期内滚动预估(OKX raw fundingRate 语义),结算后自然冻结';
COMMENT ON COLUMN funding_rates.interval_seconds IS '期次周期(8h=28800;存在 4h/1h 合约,不写死)';
COMMENT ON COLUMN funding_rates.source IS 'EXCHANGE=本所采集/回填;PROXY_BINANCE=跨所代理(消费侧须声明基差)';
