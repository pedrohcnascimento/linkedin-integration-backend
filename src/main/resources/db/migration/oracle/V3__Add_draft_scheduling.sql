ALTER TABLE content_draft
    ADD scheduled_at VARCHAR2(64 CHAR);

CREATE INDEX idx_content_draft_scheduled
    ON content_draft(status, scheduled_at);
