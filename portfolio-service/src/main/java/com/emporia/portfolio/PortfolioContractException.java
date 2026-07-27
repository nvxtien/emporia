package com.emporia.portfolio;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
class PortfolioContractException extends RuntimeException {

    PortfolioContractException(final String message) {
        super(message);
    }

    PortfolioContractException(
            final String message,
            final Throwable cause) {
        super(message, cause);
    }
}
