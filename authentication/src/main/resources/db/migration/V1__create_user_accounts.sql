CREATE TABLE user_account (
    id UUID PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    email VARCHAR(320) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_authority (
    user_id UUID NOT NULL REFERENCES user_account (id) ON DELETE CASCADE,
    authority VARCHAR(100) NOT NULL,
    PRIMARY KEY (user_id, authority)
);
