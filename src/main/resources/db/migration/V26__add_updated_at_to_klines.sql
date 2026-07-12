-- TD-002: klines 表加 updated_at，使 upsert 可区分实时更新与历史修正
ALTER TABLE klines ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
