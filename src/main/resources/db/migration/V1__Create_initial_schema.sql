-- SQLite compatibility:
-- Use TEXT for UUIDs and Timestamps.
-- Enforce foreign keys manually if needed by pragma, but we define them here.

CREATE TABLE app_user (
    id TEXT PRIMARY KEY,
    email TEXT UNIQUE NOT NULL,
    display_name TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE linkedin_authorization (
    id TEXT PRIMARY KEY,
    app_user_id TEXT NOT NULL,
    member_subject TEXT,
    encrypted_access_token TEXT NOT NULL,
    encrypted_refresh_token TEXT,
    scopes TEXT NOT NULL,
    expires_at TEXT,
    status TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    CONSTRAINT fk_linkedin_auth_user FOREIGN KEY (app_user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT uq_linkedin_auth_user UNIQUE (app_user_id)
);

CREATE TABLE content_draft (
    id TEXT PRIMARY KEY,
    app_user_id TEXT NOT NULL,
    text TEXT,
    media_category TEXT,
    original_url TEXT,
    title TEXT,
    status TEXT NOT NULL,
    approved_at TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    CONSTRAINT fk_content_draft_user FOREIGN KEY (app_user_id) REFERENCES app_user(id) ON DELETE CASCADE
);

CREATE TABLE publication (
    id TEXT PRIMARY KEY,
    app_user_id TEXT NOT NULL,
    draft_id TEXT,
    external_post_id TEXT,
    request_fingerprint TEXT NOT NULL,
    status TEXT NOT NULL,
    failure_code TEXT,
    published_at TEXT,
    created_at TEXT NOT NULL,
    CONSTRAINT fk_publication_user FOREIGN KEY (app_user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_publication_draft FOREIGN KEY (draft_id) REFERENCES content_draft(id) ON DELETE SET NULL,
    CONSTRAINT uq_publication_fingerprint UNIQUE (app_user_id, request_fingerprint)
);

CREATE TABLE opportunity (
    id TEXT PRIMARY KEY,
    app_user_id TEXT NOT NULL,
    source TEXT NOT NULL,
    external_url TEXT,
    title TEXT NOT NULL,
    company_name TEXT NOT NULL,
    location TEXT,
    description_snapshot TEXT,
    match_score INTEGER,
    status TEXT NOT NULL,
    found_at TEXT NOT NULL,
    expires_at TEXT,
    CONSTRAINT fk_opportunity_user FOREIGN KEY (app_user_id) REFERENCES app_user(id) ON DELETE CASCADE
);

CREATE TABLE oauth_transaction (
    id TEXT PRIMARY KEY,
    app_user_id TEXT NOT NULL,
    state_hash TEXT NOT NULL UNIQUE,
    scopes TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    consumed_at TEXT,
    created_at TEXT NOT NULL,
    CONSTRAINT fk_oauth_transaction_user FOREIGN KEY (app_user_id) REFERENCES app_user(id) ON DELETE CASCADE
);

CREATE TABLE audit_event (
    id TEXT PRIMARY KEY,
    app_user_id TEXT,
    event_type TEXT NOT NULL,
    resource_type TEXT,
    resource_id TEXT,
    outcome TEXT NOT NULL,
    occurred_at TEXT NOT NULL,
    metadata_sanitized TEXT,
    CONSTRAINT fk_audit_event_user FOREIGN KEY (app_user_id) REFERENCES app_user(id) ON DELETE SET NULL
);

-- Indexes for performance
CREATE INDEX idx_publication_draft_id ON publication(draft_id);
CREATE INDEX idx_opportunity_status ON opportunity(status);
CREATE INDEX idx_oauth_transaction_expires ON oauth_transaction(expires_at);

