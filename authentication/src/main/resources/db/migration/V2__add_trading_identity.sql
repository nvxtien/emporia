ALTER TABLE user_account
    ADD COLUMN desk VARCHAR(100) NOT NULL DEFAULT 'default';

ALTER TABLE user_account
    ADD COLUMN can_trade BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_user_account_desk ON user_account (desk);
