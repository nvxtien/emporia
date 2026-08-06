package com.emporia.ordermanagement.disruptor;

public final class HotPathRejectedException extends RuntimeException {
    private final int status;
    private final String reason;

    public HotPathRejectedException(int status, String reason, String message) {
        super(message);
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