package com.emporia.portfolio;

import com.emporia.portfolio.PortfolioAdminAuditService.AdminAuditFilter;
import com.emporia.portfolio.PortfolioAdminAuditService.AdminAuditPage;
import com.emporia.portfolio.PortfolioContracts.Balance;
import com.emporia.portfolio.PortfolioContracts.PortfolioState;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/portfolio")
class PortfolioAdminController {

    private final PortfolioReceiptService portfolios;
    private final PortfolioAdminAuditService audit;

    PortfolioAdminController(
            final PortfolioReceiptService portfolios,
            final PortfolioAdminAuditService audit) {
        this.portfolios = portfolios;
        this.audit = audit;
    }

    @GetMapping(
            value = "/state/{clientId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    PortfolioState state(
            @AuthenticationPrincipal final Jwt jwt,
            @PathVariable final long clientId) {
        requireAdmin(jwt);
        return portfolios.state(clientId);
    }

    @PostMapping(
            value = "/state/{clientId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    PortfolioState provision(
            @AuthenticationPrincipal final Jwt jwt,
            @PathVariable final long clientId,
            @RequestBody final ProvisionPortfolioRequest request,
            @RequestHeader(value = "X-Request-Id", required = false) final String requestId) {
        requireAdmin(jwt);
        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Request body is required");
        }
        if (request.firstTransactionId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "firstTransactionId is required");
        }
        try {
            final PortfolioState state =
                    portfolios.provision(
                            clientId,
                            request.firstTransactionId(),
                            request.balances());
            audit.record(
                    AdminAuditContext.from(jwt, requestId),
                    "PORTFOLIO_PROVISIONED",
                    "PORTFOLIO",
                    String.valueOf(clientId),
                    null,
                    state,
                    null);
            return state;
        } catch (final PortfolioAlreadyExistsException error) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    error.getMessage(),
                    error);
        } catch (final PortfolioContractException error) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    error.getMessage(),
                    error);
        }
    }

    @GetMapping(
            value = "/audit/events",
            produces = MediaType.APPLICATION_JSON_VALUE)
    AdminAuditPage auditEvents(
            @AuthenticationPrincipal final Jwt jwt,
            @RequestParam(required = false) final String actor,
            @RequestParam(required = false) final String action,
            @RequestParam(required = false) final String entityType,
            @RequestParam(required = false) final String entityId,
            @RequestParam(required = false) final String result,
            @RequestParam(defaultValue = "0") final int page,
            @RequestParam(defaultValue = "50") final int size) {
        requireAdmin(jwt);
        return audit.list(new AdminAuditFilter(
                actor,
                action,
                entityType,
                entityId,
                result,
                page,
                size));
    }

    private void requireAdmin(final Jwt jwt) {
        if (jwt == null || !authorities(jwt.getClaim("authorities")).contains("ROLE_ADMIN")) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Administrator access required");
        }
    }

    private static List<String> authorities(final Object claim) {
        if (claim instanceof final Collection<?> collection) {
            return collection.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .toList();
        }
        if (claim instanceof final String text) {
            return Arrays.stream(text.split("[,\\s]+"))
                    .filter(value -> !value.isBlank())
                    .toList();
        }
        return List.of();
    }
}

record ProvisionPortfolioRequest(
        Long firstTransactionId,
        List<Balance> balances) {
}
