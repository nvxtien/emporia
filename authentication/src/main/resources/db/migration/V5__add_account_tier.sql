ALTER TABLE user_account
    ADD COLUMN tier VARCHAR(50) NOT NULL DEFAULT 'RETAIL';

CREATE INDEX idx_user_account_tier ON user_account (tier);
