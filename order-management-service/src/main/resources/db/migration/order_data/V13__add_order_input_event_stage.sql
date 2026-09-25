ALTER TABLE order_input_event
    ADD COLUMN stage VARCHAR(16) NOT NULL DEFAULT 'RECEIVED';

CREATE INDEX idx_order_input_event_stage_sequence
    ON order_input_event (stage, sequence_id);
