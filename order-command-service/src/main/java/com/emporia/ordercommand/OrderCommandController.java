package com.emporia.ordercommand;

import com.emporia.events.TradingEvents.CommandType;
import com.emporia.events.TradingEvents.CancelAllView;
import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.TradingEvents.OrderView;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;

@RestController
@RequestMapping("/orders")
class OrderCommandController {
    private static final Set<String> DESTINATIONS = Set.of("DMA", "SMART", "VWAP");
    /**
     * Required on every mutating endpoint. A submit can time out while the order
     * is in fact created, so a caller must be able to retry without risking a
     * duplicate; that is only possible if the retry is recognisable.
     */
    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 200;
    private final StaticDataClient staticData;
    private final KafkaCommandGateway commands;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observations;

    OrderCommandController(StaticDataClient staticData, KafkaCommandGateway commands, ObjectMapper objectMapper,
                           ObservationRegistry observations) {
        this.staticData = staticData;
        this.commands = commands;
        this.objectMapper = objectMapper;
        this.observations = observations;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    OrderView create(@AuthenticationPrincipal Jwt jwt,
                     @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                     @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
                     @Valid @RequestBody CreateOrderRequest request) {
        UUID orderId = UUID.randomUUID();
        UUID commandId = commandId(idempotencyKey, jwt);
        return submit("create", destinationTag(request.destination()), orderId, () -> {
            requireTrader(jwt);
            ListingSnapshot listing = staticData.get(request.listingId(), authorization);
            String destination = normalizeDestination(request.destination());
            OrderCommand command = new OrderCommand(SCHEMA_VERSION, commandId, CommandType.CREATE,
                    jwt.getSubject(), desk(jwt), Instant.now(), orderId, null, listing, request.side(), request.type(),
                    request.quantity(), request.limitPrice(), destination,
                    request.originatorReference() == null || request.originatorReference().isBlank()
                            ? UUID.randomUUID().toString() : request.originatorReference().strip(),
                    request.parentOrderId(), request.executionParameters() == null ? Map.of() : request.executionParameters());
            return read(commands.send(command), OrderView.class);
        });
    }

    @PutMapping("/{orderId}")
    OrderView modify(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId,
                     @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
                     @Valid @RequestBody ModifyOrderRequest request) {
        UUID commandId = commandId(idempotencyKey, jwt);
        return submit("modify", "none", orderId, () -> {
            requireTrader(jwt);
            OrderCommand command = new OrderCommand(SCHEMA_VERSION, commandId, CommandType.MODIFY,
                    jwt.getSubject(), desk(jwt), Instant.now(), orderId, request.expectedVersion(), null, null, null,
                    request.quantity(), request.limitPrice(), null, null, null, Map.of());
            return read(commands.send(command), OrderView.class);
        });
    }

    @PostMapping("/{orderId}/cancel")
    OrderView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId,
                     @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey) {
        UUID commandId = commandId(idempotencyKey, jwt);
        return submit("cancel", "none", orderId, () -> {
            requireTrader(jwt);
            OrderCommand command = new OrderCommand(SCHEMA_VERSION, commandId, CommandType.CANCEL,
                    jwt.getSubject(), desk(jwt), Instant.now(), orderId, null, null, null, null,
                    null, null, null, null, null, Map.of());
            return read(commands.send(command), OrderView.class);
        });
    }

    @PostMapping("/cancel-all")
    CancelAllView cancelAll(@AuthenticationPrincipal Jwt jwt,
                            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey) {
        UUID commandId = commandId(idempotencyKey, jwt);
        return submit("cancel_all", "none", null, () -> {
            requireTrader(jwt);
            OrderCommand command = new OrderCommand(SCHEMA_VERSION, commandId, CommandType.CANCEL_ALL,
                    jwt.getSubject(), desk(jwt), Instant.now(), null, null, null, null, null,
                    null, null, null, null, null, Map.of());
            return read(commands.send(command), CancelAllView.class);
        });
    }

    /**
     * Derives the command id from the caller's idempotency key so that a retry
     * is deduplicated instead of creating a second order.
     *
     * <p>order-management-service already returns the cached result for a
     * {@code commandId} it has seen before. That protection was unreachable
     * while this method generated a random id per HTTP request: a client that
     * retried after a timeout presented a new id every time and got a duplicate
     * order. Deriving the id from a caller-supplied key is what makes the
     * existing deduplication actually apply.
     *
     * <p>Keys are scoped by subject so two users cannot collide, and prefixed so
     * the derivation can be changed later without silently reusing old ids.
     *
     * <p>On a retry the handler returns before reaching {@code create()}, so the
     * freshly generated {@code orderId} is discarded and the caller receives the
     * original order.
     */
    private UUID commandId(String idempotencyKey, Jwt jwt) {
        String key = idempotencyKey == null ? "" : idempotencyKey.strip();
        if (key.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The " + IDEMPOTENCY_KEY + " header is required so that a retry cannot create a duplicate order");
        }
        if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    IDEMPOTENCY_KEY + " must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
        byte[] scoped = ("emporia-v1:" + jwt.getSubject() + ':' + key).getBytes(StandardCharsets.UTF_8);
        return UUID.nameUUIDFromBytes(scoped);
    }

    /**
     * Records {@code emporia.order.submit} around the whole command path. The
     * outcome tag is set in the finally block so rejections and timeouts are
     * recorded too, not just successes.
     */
    private <T> T submit(String operation, String destination, UUID orderId, Supplier<T> action) {
        Observation observation = Observation.createNotStarted("emporia.order.submit", observations)
                .lowCardinalityKeyValue("operation", operation)
                .lowCardinalityKeyValue("destination", destination);
        if (orderId != null) {
            observation.highCardinalityKeyValue("order_id", orderId.toString());
        }
        observation.start();
        String outcome = "success";
        try {
            try (Observation.Scope ignored = observation.openScope()) {
                return action.get();
            }
        } catch (RuntimeException exception) {
            outcome = outcomeOf(exception);
            observation.error(exception);
            throw exception;
        } finally {
            observation.lowCardinalityKeyValue("outcome", outcome).stop();
        }
    }

    private static String outcomeOf(RuntimeException exception) {
        if (exception instanceof ResponseStatusException statusException) {
            int status = statusException.getStatusCode().value();
            if (status == HttpStatus.GATEWAY_TIMEOUT.value()) return "timeout";
            return status < HttpStatus.INTERNAL_SERVER_ERROR.value() ? "rejected" : "error";
        }
        return exception instanceof IllegalArgumentException ? "rejected" : "error";
    }

    /** Tag-safe destination: never throws, so a bad value is still recorded. */
    private static String destinationTag(String value) {
        if (value == null || value.isBlank()) return "dma";
        String destination = value.strip().toUpperCase(Locale.ROOT);
        return DESTINATIONS.contains(destination) ? destination.toLowerCase(Locale.ROOT) : "none";
    }

    private String desk(Jwt jwt) {
        String desk = jwt.getClaimAsString("desk");
        return desk == null || desk.isBlank() ? jwt.getSubject() : desk;
    }

    /**
     * The permission half of {@code emporia.risk.check}. There is no risk
     * service yet; today's pre-trade checks are split between this permission
     * gate and the quantity/price validation in order-management-service, and
     * both report under this observation with a different {@code reason}.
     */
    private void requireTrader(Jwt jwt) {
        Observation observation = Observation.createNotStarted("emporia.risk.check", observations).start();
        boolean allowed;
        try {
            allowed = Boolean.TRUE.equals(jwt.getClaim("can_trade"));
            observation.lowCardinalityKeyValue("decision", allowed ? "allow" : "deny")
                    .lowCardinalityKeyValue("reason", allowed ? "ok" : "permission");
        } finally {
            observation.stop();
        }
        if (!allowed) {
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
