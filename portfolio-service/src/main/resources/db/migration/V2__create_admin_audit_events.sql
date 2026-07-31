CREATE TABLE admin_audit_event (
    id UUID PRIMARY KEY,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    actor_subject VARCHAR(200) NOT NULL,
    actor_username VARCHAR(200) NOT NULL,
    actor_desk VARCHAR(100) NOT NULL,
    action VARCHAR(80) NOT NULL,
    entity_type VARCHAR(80) NOT NULL,
    entity_id VARCHAR(120) NOT NULL,
    result VARCHAR(30) NOT NULL,
    request_id VARCHAR(120),
    before_json TEXT,
    after_json TEXT,
    metadata_json TEXT
);

CREATE INDEX idx_portfolio_admin_audit_occurred_at
    ON admin_audit_event (occurred_at DESC);
CREATE INDEX idx_portfolio_admin_audit_actor
    ON admin_audit_event (actor_subject);
CREATE INDEX idx_portfolio_admin_audit_action
    ON admin_audit_event (action);
CREATE INDEX idx_portfolio_admin_audit_entity
    ON admin_audit_event (entity_type, entity_id);
