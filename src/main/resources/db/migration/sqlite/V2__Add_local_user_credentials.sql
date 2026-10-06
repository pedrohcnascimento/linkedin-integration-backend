ALTER TABLE app_user
    ADD COLUMN password_hash TEXT NOT NULL DEFAULT '';

CREATE UNIQUE INDEX uq_app_user_email_normalized
    ON app_user(lower(email));
