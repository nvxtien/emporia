package com.emporia.ordermanagement.repository;

import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.ordermanagement.model.TradingOrder;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TradingOrderRepository extends JpaRepository<TradingOrder, UUID> {
    List<TradingOrder> findByUserSubjectOrderByCreatedAtDesc(String userSubject);
    List<TradingOrder> findByDeskIdOrderByCreatedAtDesc(String deskId);
    Optional<TradingOrder> findByIdAndUserSubject(UUID id, String userSubject);
    Optional<TradingOrder> findByIdAndDeskId(UUID id, String deskId);
    List<TradingOrder> findByUserSubjectAndStatusInOrderByCreatedAtDesc(String userSubject, Collection<OrderStatus> statuses);
    List<TradingOrder> findByDeskIdAndStatusInOrderByCreatedAtDesc(String deskId, Collection<OrderStatus> statuses);
    List<TradingOrder> findByParentOrderIdAndStatusIn(UUID parentOrderId, Collection<OrderStatus> statuses);
    List<TradingOrder> findByParentOrderIdOrderByCreatedAtAsc(UUID parentOrderId);
    List<TradingOrder> findByStatusInAndParentOrderIdIsNullOrderByCreatedAtAsc(Collection<OrderStatus> statuses);
    List<TradingOrder> findByParentOrderIdIsNullAndDestinationInOrderByUpdatedAtDesc(
            Collection<String> destinations,
            Pageable pageable);
    List<TradingOrder> findByDeskIdAndParentOrderIdIsNullAndDestinationInOrderByUpdatedAtDesc(
            String deskId,
            Collection<String> destinations,
            Pageable pageable);
    long countByParentOrderId(UUID parentOrderId);
    long countByStatus(OrderStatus status);
    long countByStatusIn(Collection<OrderStatus> statuses);
    long countByTargetStatusAndStatusIn(OrderStatus targetStatus, Collection<OrderStatus> statuses);
}
