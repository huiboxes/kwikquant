-- TD-006: audit_logs 复合索引优化活动流查询
CREATE INDEX idx_audit_logs_actor_action_created
    ON audit_logs (actor_user_id, action, created_at DESC);
