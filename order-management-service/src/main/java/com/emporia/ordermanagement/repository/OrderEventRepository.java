package com.emporia.ordermanagement.repository;

import com.emporia.ordermanagement.model.OrderEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OrderEventRepository extends JpaRepository<OrderEvent, UUID> {
    List<OrderEvent> findByOrderIdOrderByOccurredAtAsc(UUID orderId);
    List<OrderEvent> findByCommandIdOrderByOccurredAtAsc(UUID commandId);
}
