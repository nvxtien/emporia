CREATE TABLE watchlist_entry (
    id UUID PRIMARY KEY,
    user_subject VARCHAR(200) NOT NULL,
    listing_id BIGINT NOT NULL,
    display_order INTEGER NOT NULL,
    added_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (user_subject, listing_id)
);

CREATE INDEX idx_watchlist_entry_user ON watchlist_entry (user_subject, display_order);
