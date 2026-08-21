-- 单节点 lease 表:显式化单实例部署约束。
-- token registry/锁/confirmToken 均为 JVM 内存态,单节点前提;误部署多实例会致两实例都
-- reconcile RUNNING strategies → 两份 worker 持两份 token 互相 revoke 抢占(资损级)。
-- 本表把"单节点"从隐式假设变 DB 强制:app 启动 acquire lease(id=1 单行),活跃 lease 被
-- 另一实例持有(last_seen_at 未过期且非自己)→ 启动失败 exit 1(单节点不变量)。
-- 崩溃恢复:前一实例 kill -9(无 @PreDestroy release)→ heartbeat 停 → last_seen_at 超阈值
-- → 新实例启动检测到过期 → acquire(覆盖)→ 接管。stale 阈值 = heartbeat 30s × 3。
-- 正常停机:@PreDestroy release(node_id 置空)→ 新实例无活跃 lease 直接 acquire。
CREATE TABLE app_lease (
    id            SMALLINT     NOT NULL,
    node_id       VARCHAR(64)  NOT NULL DEFAULT '',
    acquired_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_app_lease PRIMARY KEY (id)
);
-- 单行 lease(id 固定=1),初始化空 lease(node_id='' 即无实例持有)。
INSERT INTO app_lease (id) VALUES (1);
COMMENT ON TABLE app_lease IS '单节点 lease(单行 id=1):显式化单实例部署约束,第二实例启动被拒';
COMMENT ON COLUMN app_lease.node_id IS '持有 lease 的节点 ID(env KWIKQUANT_LEASE_NODE_ID 或 hostname;空=无实例持有)';
COMMENT ON COLUMN app_lease.acquired_at IS '当前实例 acquire 时间(重启重置,新 lease 周期)';
COMMENT ON COLUMN app_lease.last_seen_at IS '最后一次 heartbeat 时间(超 stale 阈值=实例崩溃,可被新实例接管)';
