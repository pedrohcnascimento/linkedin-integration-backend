ALTER TABLE app_user
    ADD password_hash VARCHAR2(255 CHAR) DEFAULT '' NOT NULL;

CREATE UNIQUE INDEX uq_app_user_email_normalized
    ON app_user(LOWER(email));
