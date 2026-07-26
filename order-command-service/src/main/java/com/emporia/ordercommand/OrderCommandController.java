package com.emporia.ordercommand;

import com.emporia.events.TradingEvents.CommandType;
import com.emporia.events.TradingEvents.CancelAllView;
import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.TradingEvents.OrderView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;

@RestController
@RequestMapping("/orders")
class OrderCommandController {
    private static final Set<String> DESTINATIONS = Set.of("DMA", "SMART", "VWAP");
    private final StaticDataClient staticData;
    private final KafkaCommandGateway commands;
    private final ObjectMapper objectMapper;

    OrderCommandController(StaticDataClient staticData, KafkaCommandGateway commands, ObjectMapper objectMapper) {
        this.staticData = staticData;
        this.commands = commands;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    OrderView create(@AuthenticationPrincipal Jwt jwt,
                     @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                     @Valid @RequestBody CreateOrderRequest request) {
        requireTrader(jwt);
        ListingSnapshot listing = staticData.get(request.listingId(), authorization);
        String destination = normalizeDestination(request.destination());
        UUID orderId = UUID.randomUUID();
        OrderCommand command = new OrderCommand(SCHEMA_VERSION, UUID.randomUUID(), CommandType.CREATE,
                jwt.getSubject(), desk(jwt), Instant.now(), orderId, null, listing, request.side(), request.type(),
                request.quantity(), request.limitPrice(), destination,
                request.originatorReference() == null || request.originatorReference().isBlank()
                        ? UUID.randomUUID().toString() : request.originatorReference().strip(),
                request.parentOrderId(), request.executionParameters() == null ? Map.of() : request.executionParameters());
        return read(commands.send(command), OrderView.class);
    }

    @PutMapping("/{orderId}")
    OrderView modify(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId,
                     @Valid @RequestBody ModifyOrderRequest request) {
        requireTrader(jwt);
        OrderCommand command = new OrderCommand(SCHEMA_VERSION, UUID.randomUUID(), CommandType.MODIFY,
                jwt.getSubject(), desk(jwt), Instant.now(), orderId, request.expectedVersion(), null, null, null,
                request.quantity(), request.limitPrice(), null, null, null, Map.of());
        return read(commands.send(command), OrderView.class);
    }

    @PostMapping("/{orderId}/cancel")
    OrderView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        requireTrader(jwt);
        OrderCommand command = new OrderCommand(SCHEMA_VERSION, UUID.randomUUID(), CommandType.CANCEL,
                jwt.getSubject(), desk(jwt), Instant.now(), orderId, null, null, null, null,
                null, null, null, null, null, Map.of());
        return read(commands.send(command), OrderView.class);
    }

    @PostMapping("/cancel-all")
    CancelAllView cancelAll(@AuthenticationPrincipal Jwt jwt) {
        requireTrader(jwt);
        OrderCommand command = new OrderCommand(SCHEMA_VERSION, UUID.randomUUID(), CommandType.CANCEL_ALL,
                jwt.getSubject(), desk(jwt), Instant.now(), null, null, null, null, null,
                null, null, null, null, null, Map.of());
        return read(commands.send(command), CancelAllView.class);
    }

    private String desk(Jwt jwt) {
        String desk = jwt.getClaimAsString("desk");
        return desk == null || desk.isBlank() ? jwt.getSubject() : desk;
    }

    private void requireTrader(Jwt jwt) {
        if (!Boolean.TRUE.equals(jwt.getClaim("can_trade"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Trading permission is required");
        }
    }

    private String normalizeDestination(String value) {
        String destination = value == null || value.isBlank() ? "DMA" : value.strip().toUpperCase(Locale.ROOT);
        if (!DESTINATIONS.contains(destination)) throw new IllegalArgumentException("Destination must be DMA, SMART, or VWAP");
        return destination;
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Order processor returned invalid JSON", exception);
        }
    }

    record CreateOrderRequest(@NotNull Long listingId, @NotNull OrderSide side, @NotNull OrderType type,
                              @NotNull @DecimalMin("0.000001") BigDecimal quantity, BigDecimal limitPrice,
                              @Size(max = 32) String destination, @Size(max = 100) String originatorReference,
                              UUID parentOrderId, Map<String, Object> executionParameters) { }

    record ModifyOrderRequest(@NotNull Long expectedVersion,
                              @NotNull @DecimalMin("0.000001") BigDecimal quantity, BigDecimal limitPrice) { }
}
