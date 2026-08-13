CREATE TABLE fix_session_state (
    mic              VARCHAR(16) PRIMARY KEY,
    session_date     DATE NOT NULL,
    outgoing_seq_num INTEGER NOT NULL CHECK (outgoing_seq_num > 0),
    incoming_seq_num INTEGER NOT NULL CHECK (incoming_seq_num > 0),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
