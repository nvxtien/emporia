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

    /**
     * Every order in these statuses, a page at a time, walked by key rather than
     * by offset. Used once at startup to fill the live-order store before the
     * service accepts traffic.
     *
     * <p><b>Keyset, not offset, and the difference is not academic.</b> An
     * offset page had to sort the whole live set to establish an order before
     * it could skip - measured at 189,000 live orders, that was an external
     * merge spilling 31 MB to disk, 1,435 ms for a single page, repeated for
     * every page. Walking {@code id > lastSeen} lets Postgres index-scan the
     * primary key instead: no sort, 240 ms per page, and the cost stays flat as
     * pages advance instead of growing with them.
     *
     * <p>Returns a {@code List} rather than a {@code Page} deliberately: a Page
     * runs a {@code count(*)} to report totals, and nothing here needs the
     * total.
     */
    List<TradingOrder> findByStatusInAndIdGreaterThanOrderByIdAsc(
            Collection<OrderStatus> statuses, UUID after,
            org.springframework.data.domain.Pageable pageable);
    long countByTargetStatusAndStatusIn(OrderStatus targetStatus, Collection<OrderStatus> statuses);
}
