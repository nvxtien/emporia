package com.emporia.ordermanagement.repository;

import com.emporia.ordermanagement.model.OrderEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface OrderEventRepository extends JpaRepository<OrderEvent, UUID> {
    List<OrderEvent> findByOrderIdOrderByOccurredAtAsc(UUID orderId);
    /**
     * Fetches the order alongside its events.
     *
     * <p>{@code OrderEvent.domainEvent()} reads the owning order's subject and
     * desk, and the association is lazy. Callers on the hot path run without a
     * transaction - the ring's consumer has no session - so loading the events
     * alone leaves an uninitialised proxy that throws the moment the event is
     * converted. Joining the order into the same query keeps that off the
     * caller's hands.
     */
    @Query("select e from OrderEvent e join fetch e.order where e.commandId = ?1 order by e.occurredAt asc")
    List<OrderEvent> findByCommandIdOrderByOccurredAtAsc(UUID commandId);
}
