package com.emporia.execution;

import java.util.Objects;

/**
 * Result of checking whether OMS should accept a new order command.
 */
public record OrderIntakeReadiness(boolean readyToAccept, String reason, String message) {
    private static final OrderIntakeReadiness READY =
            new OrderIntakeReadiness(true, "ready", "Execution venue is ready");

    public OrderIntakeReadiness {
        reason = Objects.requireNonNull(reason, "reason");
        message = Objects.requireNonNull(message, "message");
        if (!readyToAccept && (reason.isBlank() || message.isBlank())) {
            throw new IllegalArgumentException("not-ready intake state must carry a reason and message");
        }
    }

    public static OrderIntakeReadiness ready() {
        return READY;
    }

    public static OrderIntakeReadiness notReady(String reason, String message) {
        return new OrderIntakeReadiness(false, reason, message);
    }
}
