package com.emporia.ordermanagement.service;

/** Retryable signal: the BLP cannot answer from its in-memory state yet. */
public final class HotPathUnavailableException extends RuntimeException {
    public HotPathUnavailableException(String message) {
        super(message);
    }
}
