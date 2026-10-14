ALTER TABLE repos ADD COLUMN IF NOT EXISTS error_message VARCHAR(2000);

CREATE INDEX IF NOT EXISTS idx_repos_connected_by_user_id ON repos (connected_by_user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_id ON refresh_tokens (user_id);
