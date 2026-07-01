CREATE TABLE notification_preferences (
    id              BIGSERIAL       PRIMARY KEY,
    user_id         BIGINT          NOT NULL,
    event_type      VARCHAR(32)     NOT NULL,
    channel_type    VARCHAR(16)     NOT NULL,
    enabled         BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_notif_pref_user_evt_ch ON notification_preferences (user_id, event_type, channel_type);
CREATE INDEX idx_notif_pref_user ON notification_preferences (user_id);
