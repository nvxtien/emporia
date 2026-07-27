package com.emporia.portfolio;

import com.emporia.portfolio.PortfolioContracts.RiskSeed;
import com.emporia.portfolio.PortfolioContracts.Snapshot;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/internal/v1")
class PortfolioController {

    private final PortfolioReceiptService portfolios;
    private final ObjectMapper objectMapper;

    PortfolioController(
            final PortfolioReceiptService portfolios,
            final ObjectMapper objectMapper) {
        this.portfolios = portfolios;
        this.objectMapper = objectMapper;
    }

    @GetMapping(
            value = "/portfolios/{clientId}/risk-seed",
            produces = MediaType.APPLICATION_JSON_VALUE)
    RiskSeed load(@PathVariable final long clientId) {
        return portfolios.load(clientId);
    }

    @PutMapping(
            value = "/portfolio-snapshots/{deliveryId}/{clientId}",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> publish(
            @PathVariable final long deliveryId,
            @PathVariable final long clientId,
            @RequestHeader("Idempotency-Key")
            final String eventId,
            @RequestBody final byte[] payload) {
        final Snapshot snapshot;
        try {
            snapshot = objectMapper.readValue(
                    payload,
                    Snapshot.class);
        } catch (final JacksonException error) {
            throw new PortfolioContractException(
                    "snapshot body is not valid JSON",
                    error);
        }
        portfolios.apply(
                deliveryId,
                clientId,
                eventId,
                payload,
                snapshot);
        return ResponseEntity.noContent().build();
    }
}
