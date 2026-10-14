-- Which source-control provider a repo lives on. Only 'github' exists today;
-- the column is here so adding GitLab/Bitbucket later needs no backfill.
ALTER TABLE repos ADD COLUMN IF NOT EXISTS provider VARCHAR(32) NOT NULL DEFAULT 'github';
