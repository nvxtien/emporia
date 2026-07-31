package com.emporia.portfolio;

class PortfolioAlreadyExistsException extends RuntimeException {

    PortfolioAlreadyExistsException(final long clientId) {
        super("Portfolio already exists for client " + clientId);
    }

    PortfolioAlreadyExistsException(
            final long clientId,
            final Throwable cause) {
        super("Portfolio already exists for client " + clientId, cause);
    }
}
