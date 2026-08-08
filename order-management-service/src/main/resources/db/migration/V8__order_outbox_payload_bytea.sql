-- Old rows are still JSON text, not SBE bytes; keeping them around as
-- reinterpreted "bytea" would let Debezium relay them and crash every
-- consumer's SbeEncoderDecoder.decode call. Dropping them is safe: this
-- table is a transient in-flight delivery buffer, not permanent history,
-- and only pre-CDC dev/test rows are affected.
DELETE FROM order_outbox;
ALTER TABLE order_outbox DROP COLUMN payload;
ALTER TABLE order_outbox ADD COLUMN payload BYTEA NOT NULL;
DROP INDEX IF EXISTS idx_order_outbox_pending;
ALTER TABLE order_outbox DROP COLUMN status;
ALTER TABLE order_outbox DROP COLUMN attempt_count;
ALTER TABLE order_outbox DROP COLUMN last_error;
ALTER TABLE order_outbox DROP COLUMN published_at;
