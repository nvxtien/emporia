package com.emporia.ordermanagement.controller;

import com.emporia.events.TradingEvents.CommandType;
import com.emporia.events.TradingEvents.CancelAllView;
import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.TradingEvents.OrderView;
import com.emporia.events.time.DomainClock;
import com.emporia.ordermanagement.disruptor.HotPathRejectedException;
import com.emporia.ordermanagement.client.StaticDataClient;
import com.emporia.ordermanagement.dto.ProcessingOutcome;
import com.emporia.ordermanagement.service.OrderCommandHandler;
import com.emporia.ordermanagement.service.OrderInputEventRecorder;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import static com.emporia.events.TradingEvents.SCHEMA_VERSION;

/**
 * Combined in-process Order Command RestController.
 *
 * <p>Executes {@link OrderCommandHandler} directly in-process upon REST intake,
 * completely eliminating Kafka round-trips from the synchronous HTTP REST critical path.
 *
 * <p>Domain events and result records are published to Kafka asynchronously out-of-band
 * for downstream execution services, audit logs, and external consumers.
 */
@RestController
@RequestMapping("/orders")
public class OrderCommandController {
    private static final Set<String> DESTINATIONS = Set.of("DMA", "SMART", "VWAP");
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 200;

    private final StaticDataClient staticData;
    private final com.emporia.ordermanagement.disruptor.DisruptorOrderPipeline disruptorPipeline;
    private final OrderInputEventRecorder inputRecorder;
    private final ObjectMapper objectMapper;
    private final ObservationRegistry observations;

    public OrderCommandController(StaticDataClient staticData,
                                  OrderCommandHandler handler,
                                  com.emporia.ordermanagement.disruptor.DisruptorOrderPipeline disruptorPipeline,
                                  OrderInputEventRecorder inputRecorder,
                                  ObjectMapper objectMapper,
                                  ObservationRegistry observations) {
        this.staticData = staticData;
        this.disruptorPipeline = disruptorPipeline;
        this.inputRecorder = inputRecorder;
        this.objectMapper = objectMapper;
        this.observations = observations;
    }

    private final java.util.concurrent.ConcurrentHashMap<Class<?>, tools.jackson.databind.ObjectReader> jsonReaders = new java.util.concurrent.ConcurrentHashMap<>();

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderView create(@AuthenticationPrincipal Jwt jwt,
                            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
                            @Valid @RequestBody CreateOrderRequest request) {
        UUID orderId = UUID.randomUUID();
        UUID commandId = commandId(idempotencyKey, jwt);
        Observation observation = startObservation("create", destinationTag(request.destination()), orderId);
        RuntimeException error = null;
        try (Observation.Scope ignored = observation.openScope()) {
            requireTrader(jwt);
            ListingSnapshot listing = staticData.get(request.listingId(), authorization);
            String destination = normalizeDestination(request.destination());
            OrderCommand command = new OrderCommand(SCHEMA_VERSION, commandId, CommandType.CREATE,
                    jwt.getSubject(), desk(jwt), DomainClock.now(), orderId, null, listing, request.side(), request.type(),
                    request.quantity(), request.limitPrice(), destination,
                    request.originatorReference() == null || request.originatorReference().isBlank()
                            ? UUID.randomUUID().toString() : request.originatorReference().strip(),
                    request.parentOrderId(), request.executionParameters() == null ? Map.of() : request.executionParameters());

            ProcessingOutcome outcome = submit(command);
            return read(outcome.result().payload(), OrderView.class);
        } catch (RuntimeException exception) {
            error = exception;
            throw exception;
        } finally {
            stopObservation(observation, error);
        }
    }

    @PutMapping("/{orderId}")
    public OrderView modify(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId,
                            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
                            @Valid @RequestBody ModifyOrderRequest request) {
        UUID commandId = commandId(idempotencyKey, jwt);
        Observation observation = startObservation("modify", "none", orderId);
        RuntimeException error = null;
        try (Observation.Scope ignored = observation.openScope()) {
            requireTrader(jwt);
            OrderCommand command = new OrderCommand(SCHEMA_VERSION, commandId, CommandType.MODIFY,
                    jwt.getSubject(), desk(jwt), DomainClock.now(), orderId, request.expectedVersion(), null, null, null,
                    request.quantity(), request.limitPrice(), null, null, null, Map.of());

            ProcessingOutcome outcome = submit(command);
            return read(outcome.result().payload(), OrderView.class);
        } catch (RuntimeException exception) {
            error = exception;
            throw exception;
        } finally {
            stopObservation(observation, error);
        }
    }

    @PostMapping("/{orderId}/cancel")
    public OrderView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId,
                            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey) {
        UUID commandId = commandId(idempotencyKey, jwt);
        Observation observation = startObservation("cancel", "none", orderId);
        RuntimeException error = null;
        try (Observation.Scope ignored = observation.openScope()) {
            requireTrader(jwt);
            OrderCommand command = new OrderCommand(SCHEMA_VERSION, commandId, CommandType.CANCEL,
                    jwt.getSubject(), desk(jwt), DomainClock.now(), orderId, null, null, null, null,
                    null, null, null, null, null, Map.of());

            ProcessingOutcome outcome = submit(command);
            return read(outcome.result().payload(), OrderView.class);
        } catch (RuntimeException exception) {
            error = exception;
            throw exception;
        } finally {
            stopObservation(observation, error);
        }
    }

    @PostMapping("/cancel-all")
    public CancelAllView cancelAll(@AuthenticationPrincipal Jwt jwt,
                                   @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey) {
        UUID commandId = commandId(idempotencyKey, jwt);
        Observation observation = startObservation("cancel_all", "none", null);
        RuntimeException error = null;
        try (Observation.Scope ignored = observation.openScope()) {
            requireTrader(jwt);
            OrderCommand command = new OrderCommand(SCHEMA_VERSION, commandId, CommandType.CANCEL_ALL,
                    jwt.getSubject(), desk(jwt), DomainClock.now(), null, null, null, null, null,
                    null, null, null, null, null, Map.of());

            ProcessingOutcome outcome = submit(command);
            return read(outcome.result().payload(), CancelAllView.class);
        } catch (RuntimeException exception) {
            error = exception;
            throw exception;
        } finally {
            stopObservation(observation, error);
        }
    }

    private ProcessingOutcome submit(OrderCommand command) {
        inputRecorder.record(command);
        return await(command);
    }

    private ProcessingOutcome await(OrderCommand command) {
        try {
            return disruptorPipeline.submit(command).join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof HotPathRejectedException rejected) {
                throw new ResponseStatusException(HttpStatus.valueOf(rejected.status()), rejected.getMessage(), exception);
            }
            throw exception;
        }
    }

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
        String subject = jwt.getSubject();
        StringBuilder sb = new StringBuilder(11 + subject.length() + 1 + key.length());
        sb.append("emporia-v1:").append(subject).append(':').append(key);
        byte[] scoped = sb.toString().getBytes(StandardCharsets.UTF_8);
        return UUID.nameUUIDFromBytes(scoped);
    }

    private Observation startObservation(String operation, String destination, UUID orderId) {
        Observation observation = Observation.createNotStarted("emporia.order.submit", observations)
                .lowCardinalityKeyValue("operation", operation)
                .lowCardinalityKeyValue("destination", destination);
        if (orderId != null) {
            observation.highCardinalityKeyValue("order_id", orderId.toString());
        }
        return observation.start();
    }

    private void stopObservation(Observation observation, RuntimeException exception) {
        String outcome = exception == null ? "success" : outcomeOf(exception);
        if (exception != null) {
            observation.error(exception);
        }
        observation.lowCardinalityKeyValue("outcome", outcome).stop();
    }

    private static String outcomeOf(RuntimeException exception) {
        if (exception instanceof ResponseStatusException statusException) {
            int status = statusException.getStatusCode().value();
            if (status == HttpStatus.GATEWAY_TIMEOUT.value()) return "timeout";
            return status < HttpStatus.INTERNAL_SERVER_ERROR.value() ? "rejected" : "error";
        }
        return exception instanceof IllegalArgumentException ? "rejected" : "error";
    }

    private static String destinationTag(String value) {
        if (value == null || value.isBlank()) return "dma";
        String destination = value.strip().toUpperCase(Locale.ROOT);
        return DESTINATIONS.contains(destination) ? destination.toLowerCase(Locale.ROOT) : "none";
    }

    private String desk(Jwt jwt) {
        String desk = jwt.getClaimAsString("desk");
        return desk == null || desk.isBlank() ? jwt.getSubject() : desk;
    }

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
            return jsonReaders.computeIfAbsent(type, objectMapper::readerFor).readValue(json);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Order processor returned invalid JSON", exception);
        }
    }

    public record CreateOrderRequest(@NotNull Long listingId, @NotNull OrderSide side, @NotNull OrderType type,
                              @NotNull @DecimalMin("0.000001") BigDecimal quantity, BigDecimal limitPrice,
                              @Size(max = 32) String destination, @Size(max = 100) String originatorReference,
                              UUID parentOrderId, Map<String, Object> executionParameters) { }

    public record ModifyOrderRequest(@NotNull Long expectedVersion,
                              @NotNull @DecimalMin("0.000001") BigDecimal quantity, BigDecimal limitPrice) { }
}
