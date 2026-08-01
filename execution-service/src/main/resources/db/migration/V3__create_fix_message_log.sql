CREATE TABLE fix_message_log (
    mic         VARCHAR(16) NOT NULL,
    seq_num     INTEGER NOT NULL CHECK (seq_num > 0),
    msg_type    VARCHAR(4) NOT NULL,
    raw_message TEXT NOT NULL,
    sent_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (mic, seq_num)
);
