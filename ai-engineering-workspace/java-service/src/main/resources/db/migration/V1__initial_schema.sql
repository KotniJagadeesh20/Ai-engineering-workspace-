-- Baseline schema, matching what Hibernate's ddl-auto=update used to create.
-- IF NOT EXISTS so this is a no-op on databases that already have these tables.

CREATE TABLE IF NOT EXISTS users (
    id          UUID PRIMARY KEY,
    github_id   VARCHAR(255) NOT NULL UNIQUE,
    email       VARCHAR(255) UNIQUE,
    username    VARCHAR(255),
    avatar_url  VARCHAR(255),
    created_at  TIMESTAMP(6) WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS github_credentials (
    id                      UUID PRIMARY KEY,
    user_id                 UUID NOT NULL UNIQUE,
    encrypted_access_token  TEXT NOT NULL,
    expires_at              TIMESTAMP(6) WITH TIME ZONE,
    updated_at              TIMESTAMP(6) WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS refresh_tokens (
    id          UUID PRIMARY KEY,
    token_hash  VARCHAR(255) NOT NULL UNIQUE,
    user_id     UUID NOT NULL,
    expires_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    revoked     BOOLEAN NOT NULL,
    created_at  TIMESTAMP(6) WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS repos (
    id                    UUID PRIMARY KEY,
    github_url            VARCHAR(255) NOT NULL,
    name                  VARCHAR(255),
    owner                 VARCHAR(255),
    local_path            VARCHAR(255) NOT NULL,
    status                VARCHAR(255),
    connected_by_user_id  UUID NOT NULL,
    workspace_id          UUID,
    created_at            TIMESTAMP(6) WITH TIME ZONE
);
