package com.emporia.portfolio;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
class PortfolioIdempotencyConflictException
        extends RuntimeException {

    PortfolioIdempotencyConflictException(final String eventId) {
        super("event ID was reused with a different payload: " + eventId);
    }
}
