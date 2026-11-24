ALTER TABLE content_draft
    ADD COLUMN scheduled_at TEXT;

CREATE INDEX idx_content_draft_scheduled
    ON content_draft(status, scheduled_at);
