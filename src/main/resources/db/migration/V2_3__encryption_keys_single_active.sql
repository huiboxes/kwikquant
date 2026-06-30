-- 防御深度:确保同一时间最多一条 active=TRUE 的主密钥。
-- Service 层 (KeyManagementService.rotateKey) 已在事务内 deactivateAll -> insert 兜底,
-- 此 partial unique index 作为 DB 层兜底,拦截并发竞态 / 手动 SQL / 迁移出错导致的多 active 歧义。
CREATE UNIQUE INDEX IF NOT EXISTS uk_encryption_keys_single_active
    ON encryption_keys (active) WHERE active = TRUE;
