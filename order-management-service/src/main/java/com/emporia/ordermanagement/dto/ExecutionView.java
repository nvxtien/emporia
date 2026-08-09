package com.emporia.ordermanagement.dto;

import com.emporia.ordermanagement.model.Execution;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ExecutionView(
        UUID id,
        String executionReference,
        BigDecimal quantity,
        BigDecimal price,
        String venue,
        Instant executedAt) {
    public static ExecutionView from(Execution execution) {
        return new ExecutionView(
                execution.getId(),
                execution.getExecutionReference(),
                execution.getQuantity(),
                execution.getPrice(),
                execution.getVenue(),
                execution.getExecutedAt());
    }
}