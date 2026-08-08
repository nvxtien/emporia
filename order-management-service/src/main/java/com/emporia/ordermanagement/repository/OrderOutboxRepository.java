package com.emporia.ordermanagement.repository;

import com.emporia.ordermanagement.model.OrderOutboxRecord;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Debezium CDC-drains {@code order_outbox} straight from Postgres's WAL - no
 * status-based polling query needed here anymore.
 */
public interface OrderOutboxRepository extends JpaRepository<OrderOutboxRecord, Long> {
}
