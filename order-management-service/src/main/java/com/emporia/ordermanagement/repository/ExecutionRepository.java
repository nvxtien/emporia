package com.emporia.ordermanagement.repository;

import com.emporia.ordermanagement.model.Execution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ExecutionRepository extends JpaRepository<Execution, UUID> {
    List<Execution> findByOrderIdOrderByExecutedAtAsc(UUID orderId);
    boolean existsByExecutionReference(String executionReference);
}
