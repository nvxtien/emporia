package com.emporia.ordermanagement.repository;

import com.emporia.ordermanagement.model.OrderOutboxRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderOutboxRepository extends JpaRepository<OrderOutboxRecord, Long> {
    List<OrderOutboxRecord> findTop500ByStatusOrderBySequenceIdAsc(OrderOutboxRecord.Status status);
}
