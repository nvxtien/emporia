package com.emporia.portfolio;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
class PortfolioNotFoundException extends RuntimeException {

    PortfolioNotFoundException(
            final long clientId,
            final Throwable cause) {
        super(
                "portfolio client " + clientId + " was not found",
                cause);
    }
}
