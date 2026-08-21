ALTER TABLE app_lease
    ADD COLUMN owner_token UUID;

COMMENT ON COLUMN app_lease.owner_token IS
    '每个JVM启动随机生成的lease所有权令牌，避免相同node_id实例双活与ABA续租';
