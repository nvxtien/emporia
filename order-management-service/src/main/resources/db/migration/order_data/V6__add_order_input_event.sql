CREATE TABLE order_input_event (
    sequence_id BIGSERIAL PRIMARY KEY,
    command_id UUID NOT NULL,
    command_type VARCHAR(16) NOT NULL,
    user_subject VARCHAR(200) NOT NULL,
    desk_id VARCHAR(100) NOT NULL,
    schema_version INTEGER NOT NULL,
    payload TEXT NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_order_input_event_command ON order_input_event (command_id);