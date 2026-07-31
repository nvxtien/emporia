package com.emporia.ordermanagement.repository;

import com.emporia.ordermanagement.model.Execution;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ExecutionRepository extends JpaRepository<Execution, UUID> {
    List<Execution> findByOrderIdOrderByExecutedAtAsc(UUID orderId);
    boolean existsByExecutionReference(String executionReference);

    @Query("""
            select execution
            from Execution execution
            join fetch execution.order tradingOrder
            where (:deskId is null or tradingOrder.deskId = :deskId)
              and (:venue is null or execution.venue = :venue)
              and (:destination is null or tradingOrder.destination = :destination)
            order by execution.executedAt desc
            """)
    List<Execution> findRecentForAdmin(
            @Param("deskId") String deskId,
            @Param("venue") String venue,
            @Param("destination") String destination,
            Pageable pageable);
}
