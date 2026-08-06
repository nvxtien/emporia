package com.emporia.ordermanagement.disruptor;

public final class HotPathRejectedException extends RuntimeException {
    private final int status;
    private final String reason;

    public HotPathRejectedException(int status, String reason, String message) {
        this(status, reason, message, null);
    }

    public HotPathRejectedException(int status, String reason, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.reason = reason;
    }

    public int status() {
        return status;
    }

    public String reason() {
        return reason;
    }
}