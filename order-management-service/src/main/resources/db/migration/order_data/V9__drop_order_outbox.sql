-- The outbox/Debezium CDC path (emporia.orders.v1 via Kafka Connect) was
-- replaced by an in-process dispatcher; nothing has written to this table
-- since, and its only reader (the Debezium connector) has been decommissioned.
DROP TABLE order_outbox;
