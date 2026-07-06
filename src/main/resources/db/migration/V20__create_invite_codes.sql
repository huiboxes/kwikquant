-- 邀请码注册门禁(V20):注册必须提供有效邀请码,防止任意注册。
--
-- 设计:
--  - code 主键(人类可读字符串,dev 预置 KWIK-DEV-001/002;正式码由管理员 SQL INSERT 生成)
--  - max_uses/used_count 表达一次性/多次消费语义;used_count < max_uses 即可用
--  - enabled=false 或 expires_at < now() 视为失效
--  - 消费走 InviteCodeMapper.incrementUsedCount 乐观锁(WHERE used_count < max_uses),
--    并发安全不会超卖;消费失败 @Transactional 回滚用户创建
--
-- 当前不做管理接口(生成/列表/撤销),dev 用 SQL 直接操作;后续若需可加 admin controller。
CREATE TABLE invite_codes (
    code       VARCHAR(64)  PRIMARY KEY,
    max_uses   INT          NOT NULL DEFAULT 1,
    used_count INT          NOT NULL DEFAULT 0,
    expires_at TIMESTAMPTZ  NULL,
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- dev 预置邀请码(max_uses=100 方便测试,永不过期)
-- 正式环境请删除这两行或改 enabled=FALSE,改用 SQL INSERT 生成正式码
INSERT INTO invite_codes (code, max_uses, used_count, expires_at, enabled, created_at) VALUES
    ('KWIK-DEV-001', 100, 0, NULL, TRUE, now()),
    ('KWIK-DEV-002', 100, 0, NULL, TRUE, now());
