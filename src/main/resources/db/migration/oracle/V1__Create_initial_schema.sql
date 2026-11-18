CREATE TABLE app_user (
    id VARCHAR2(36 CHAR) PRIMARY KEY,
    email VARCHAR2(320 CHAR) UNIQUE NOT NULL,
    display_name VARCHAR2(255 CHAR) NOT NULL,
    status VARCHAR2(32 CHAR) NOT NULL,
    created_at VARCHAR2(64 CHAR) NOT NULL,
    updated_at VARCHAR2(64 CHAR) NOT NULL
);

CREATE TABLE linkedin_authorization (
    id VARCHAR2(36 CHAR) PRIMARY KEY,
    app_user_id VARCHAR2(36 CHAR) NOT NULL,
    member_subject VARCHAR2(255 CHAR),
    encrypted_access_token VARCHAR2(4000 CHAR) NOT NULL,
    encrypted_refresh_token VARCHAR2(4000 CHAR),
    scopes VARCHAR2(1000 CHAR) NOT NULL,
    expires_at VARCHAR2(64 CHAR),
    status VARCHAR2(32 CHAR) NOT NULL,
    created_at VARCHAR2(64 CHAR) NOT NULL,
    updated_at VARCHAR2(64 CHAR) NOT NULL,
    CONSTRAINT fk_linkedin_auth_user FOREIGN KEY (app_user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT uq_linkedin_auth_user UNIQUE (app_user_id)
);

CREATE TABLE content_draft (
    id VARCHAR2(36 CHAR) PRIMARY KEY,
    app_user_id VARCHAR2(36 CHAR) NOT NULL,
    text VARCHAR2(4000 CHAR),
    media_category VARCHAR2(64 CHAR),
    original_url VARCHAR2(2000 CHAR),
    title VARCHAR2(255 CHAR),
    status VARCHAR2(32 CHAR) NOT NULL,
    approved_at VARCHAR2(64 CHAR),
    created_at VARCHAR2(64 CHAR) NOT NULL,
    updated_at VARCHAR2(64 CHAR) NOT NULL,
    CONSTRAINT fk_content_draft_user FOREIGN KEY (app_user_id) REFERENCES app_user(id) ON DELETE CASCADE
);

CREATE TABLE publication (
    id VARCHAR2(36 CHAR) PRIMARY KEY,
    app_user_id VARCHAR2(36 CHAR) NOT NULL,
    draft_id VARCHAR2(36 CHAR),
    external_post_id VARCHAR2(255 CHAR),
    request_fingerprint VARCHAR2(255 CHAR) NOT NULL,
    status VARCHAR2(32 CHAR) NOT NULL,
    failure_code VARCHAR2(255 CHAR),
    published_at VARCHAR2(64 CHAR),
    created_at VARCHAR2(64 CHAR) NOT NULL,
    CONSTRAINT fk_publication_user FOREIGN KEY (app_user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_publication_draft FOREIGN KEY (draft_id) REFERENCES content_draft(id) ON DELETE SET NULL,
    CONSTRAINT uq_publication_fingerprint UNIQUE (app_user_id, request_fingerprint)
);

CREATE TABLE opportunity (
    id VARCHAR2(36 CHAR) PRIMARY KEY,
    app_user_id VARCHAR2(36 CHAR) NOT NULL,
    source VARCHAR2(64 CHAR) NOT NULL,
    external_url VARCHAR2(2000 CHAR),
    title VARCHAR2(255 CHAR) NOT NULL,
    company_name VARCHAR2(255 CHAR) NOT NULL,
    location VARCHAR2(255 CHAR),
    description_snapshot VARCHAR2(4000 CHAR),
    match_score NUMBER(10),
    status VARCHAR2(32 CHAR) NOT NULL,
    found_at VARCHAR2(64 CHAR) NOT NULL,
    expires_at VARCHAR2(64 CHAR),
    CONSTRAINT fk_opportunity_user FOREIGN KEY (app_user_id) REFERENCES app_user(id) ON DELETE CASCADE
);

CREATE TABLE oauth_transaction (
    id VARCHAR2(36 CHAR) PRIMARY KEY,
    app_user_id VARCHAR2(36 CHAR) NOT NULL,
    state_hash VARCHAR2(255 CHAR) NOT NULL UNIQUE,
    scopes VARCHAR2(1000 CHAR) NOT NULL,
    expires_at VARCHAR2(64 CHAR) NOT NULL,
    consumed_at VARCHAR2(64 CHAR),
    created_at VARCHAR2(64 CHAR) NOT NULL,
    CONSTRAINT fk_oauth_transaction_user FOREIGN KEY (app_user_id) REFERENCES app_user(id) ON DELETE CASCADE
);

CREATE TABLE audit_event (
    id VARCHAR2(36 CHAR) PRIMARY KEY,
    app_user_id VARCHAR2(36 CHAR),
    event_type VARCHAR2(128 CHAR) NOT NULL,
    resource_type VARCHAR2(128 CHAR),
    resource_id VARCHAR2(255 CHAR),
    outcome VARCHAR2(32 CHAR) NOT NULL,
    occurred_at VARCHAR2(64 CHAR) NOT NULL,
    metadata_sanitized VARCHAR2(4000 CHAR),
    CONSTRAINT fk_audit_event_user FOREIGN KEY (app_user_id) REFERENCES app_user(id) ON DELETE SET NULL
);

CREATE INDEX idx_publication_draft_id ON publication(draft_id);
CREATE INDEX idx_opportunity_status ON opportunity(status);
CREATE INDEX idx_oauth_transaction_expires ON oauth_transaction(expires_at);
