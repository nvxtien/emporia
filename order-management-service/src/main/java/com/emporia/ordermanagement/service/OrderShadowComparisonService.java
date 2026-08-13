package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderCommandResult;
import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.ordermanagement.dto.ProcessingOutcome;
import com.emporia.ordermanagement.model.OrderEvent;
import com.emporia.ordermanagement.model.OrderInputEvent;
import com.emporia.ordermanagement.model.ProcessedCommand;
import com.emporia.ordermanagement.model.TradingOrder;
import com.emporia.ordermanagement.repository.OrderEventRepository;
import com.emporia.ordermanagement.repository.OrderInputEventRepository;
import com.emporia.ordermanagement.repository.ProcessedCommandRepository;
import com.emporia.ordermanagement.repository.TradingOrderRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class OrderShadowComparisonService {
    private static final Field VERSION_FIELD = field(TradingOrder.class, "version");
    private static final Set<String> VOLATILE_PAYLOAD_FIELDS =
            Set.of("createdAt", "updatedAt", "occurredAt", "processedAt");

    private final OrderInputEventRepository inputEvents;
    private final ProcessedCommandRepository processed;
    private final OrderEventRepository events;
    private final ObjectMapper objectMapper;

    public OrderShadowComparisonService(OrderInputEventRepository inputEvents,
                                        ProcessedCommandRepository processed,
                                        OrderEventRepository events,
                                        ObjectMapper objectMapper) {
        this.inputEvents = inputEvents;
        this.processed = processed;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    public ShadowComparisonReport compare(int limit) {
        return compare(limit, null);
    }

    public ShadowComparisonReport compare(int limit, Long afterSequenceId) {
        int boundedLimit = Math.max(1, limit);
        List<OrderInputEvent> orderedInputs = orderedInputs(boundedLimit, afterSequenceId);
        ShadowSandbox sandbox = new ShadowSandbox();
        List<ShadowMismatch> mismatches = new ArrayList<>();
        int matched = 0;
        for (OrderInputEvent inputEvent : orderedInputs) {
            ShadowSnapshot actual = actual(inputEvent);
            ShadowSnapshot replayed = sandbox.replay(inputEvent, objectMapper);
            if (actual.equals(replayed)) {
                matched++;
                continue;
            }
            mismatches.add(new ShadowMismatch(
                    inputEvent.getSequenceId() == null ? -1L : inputEvent.getSequenceId(),
                    inputEvent.getCommandId(),
                    actual.result(),
                    replayed.result(),
                    actual.events(),
                    replayed.events()
            ));
        }
        return new ShadowComparisonReport(
                orderedInputs.size(),
                matched,
                mismatches.size(),
                orderedInputs.isEmpty() ? 0.0d : ((double) matched) / orderedInputs.size(),
                List.copyOf(mismatches)
        );
    }

    public long latestSequenceId() {
        return inputEvents.maxSequenceId();
    }

    private List<OrderInputEvent> orderedInputs(int limit, Long afterSequenceId) {
        PageRequest page = PageRequest.of(0, limit);
        if (afterSequenceId != null) {
            return inputEvents.findBySequenceIdGreaterThanOrderBySequenceIdAsc(afterSequenceId, page);
        }
        return inputEvents.findByOrderBySequenceIdDesc(page)
                .stream()
                .sorted(Comparator.comparing(OrderInputEvent::getSequenceId, Comparator.nullsLast(Long::compareTo)))
                .toList();
    }

    private ShadowSnapshot actual(OrderInputEvent inputEvent) {
        NormalizedResult result = processed.findById(inputEvent.getCommandId())
                .map(ProcessedCommand::result)
                .map(resultRecord -> normalize(resultRecord, objectMapper))
                .orElse(null);
        List<NormalizedEvent> normalizedEvents = events.findByCommandIdOrderByOccurredAtAsc(inputEvent.getCommandId())
                .stream()
                .map(OrderEvent::domainEvent)
                .map(event -> normalize(event, objectMapper))
                .toList();
        return new ShadowSnapshot(result, normalizedEvents);
    }

    private static NormalizedResult normalize(OrderCommandResult result, ObjectMapper objectMapper) {
        return new NormalizedResult(result.success(), result.status(), result.detail(),
                normalizePayload(result.payload(), objectMapper));
    }

    private static NormalizedEvent normalize(OrderDomainEvent event, ObjectMapper objectMapper) {
        return new NormalizedEvent(event.commandId(), event.orderId(), event.userSubject(), event.deskId(),
                event.eventType(), event.orderVersion(), event.status().name(),
                normalizePayload(event.payload(), objectMapper));
    }

    private static String normalizePayload(String payload, ObjectMapper objectMapper) {
        if (payload == null || payload.isBlank()) return payload;
        try {
            JsonNode normalized = objectMapper.readTree(payload).deepCopy();
            removeVolatilePayloadFields(normalized);
            return canonicalJson(normalized, objectMapper);
        } catch (Exception invalidJson) {
            return payload;
        }
    }

    private static void removeVolatilePayloadFields(JsonNode node) {
        if (node == null) return;
        if (node.isObject()) {
            ObjectNode object = node.asObject();
            object.remove(VOLATILE_PAYLOAD_FIELDS);
            for (JsonNode child : object.values()) {
                removeVolatilePayloadFields(child);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                removeVolatilePayloadFields(child);
            }
        }
    }

    private static String canonicalJson(JsonNode node, ObjectMapper objectMapper) throws Exception {
        if (node == null || node.isNull()) return "null";
        if (node.isNumber()) return node.decimalValue().stripTrailingZeros().toPlainString();
        if (node.isString()) return objectMapper.writeValueAsString(node.asString());
        if (node.isBoolean()) return Boolean.toString(node.booleanValue());
        if (node.isArray()) {
            StringBuilder builder = new StringBuilder("[");
            boolean first = true;
            for (JsonNode child : node) {
                if (!first) builder.append(',');
                builder.append(canonicalJson(child, objectMapper));
                first = false;
            }
            return builder.append(']').toString();
        }
        if (node.isObject()) {
            StringBuilder builder = new StringBuilder("{");
            boolean first = true;
            for (String name : node.propertyNames().stream().sorted().toList()) {
                if (!first) builder.append(',');
                builder.append(objectMapper.writeValueAsString(name))
                        .append(':')
                        .append(canonicalJson(node.get(name), objectMapper));
                first = false;
            }
            return builder.append('}').toString();
        }
        return node.toString();
    }

    private static Field field(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Missing field " + type.getSimpleName() + '.' + name, exception);
        }
    }

    private static void setVersion(TradingOrder order, long version) {
        try {
            VERSION_FIELD.set(order, version);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not set TradingOrder.version", exception);
        }
    }

    public record ShadowComparisonReport(int totalCommands, int matchedCommands, int mismatchedCommands,
                                         double passRate, List<ShadowMismatch> mismatches) {
    }

    public record ShadowMismatch(long sequenceId, UUID commandId,
                                 NormalizedResult actualResult, NormalizedResult replayResult,
                                 List<NormalizedEvent> actualEvents, List<NormalizedEvent> replayEvents) {
    }

    public record NormalizedResult(boolean success, int status, String detail, String payload) {
    }

    public record NormalizedEvent(UUID commandId, UUID orderId, String userSubject, String deskId,
                                  String eventType, long orderVersion, String status, String payload) {
    }

    private record ShadowSnapshot(NormalizedResult result, List<NormalizedEvent> events) {
    }

    private static final class ShadowSandbox {
        private final Map<UUID, TradingOrder> storedOrders = new HashMap<>();
        private final Map<UUID, ProcessedCommand> processedCommands = new HashMap<>();
        private final Map<UUID, List<OrderEvent>> eventsByCommand = new HashMap<>();
        private final OrderCommandHandler handler;

        private ShadowSandbox() {
            TradingOrderRepository orders = repository(
                    TradingOrderRepository.class,
                    (method, arguments) -> switch (method) {
                        case "existsById" -> storedOrders.containsKey(arguments[0]);
                        case "findByIdAndDeskId" -> {
                            TradingOrder order = storedOrders.get(arguments[0]);
                            String deskId = (String) arguments[1];
                            yield Optional.ofNullable(order)
                                    .filter(candidate -> candidate.getDeskId().equals(deskId));
                        }
                        case "findByParentOrderIdAndStatusIn" -> storedOrders.values().stream()
                                .filter(candidate -> arguments[0].equals(candidate.getParentOrderId()))
                                .filter(candidate -> ((Collection<?>) arguments[1]).contains(candidate.getStatus()))
                                .toList();
                        case "findByDeskIdAndStatusInOrderByCreatedAtDesc" -> storedOrders.values().stream()
                                .filter(candidate -> ((String) arguments[0]).equals(candidate.getDeskId()))
                                .filter(candidate -> ((Collection<?>) arguments[1]).contains(candidate.getStatus()))
                                .sorted(Comparator.comparing(TradingOrder::getCreatedAt).reversed())
                                .toList();
                        case "save", "saveAndFlush" -> {
                            TradingOrder order = (TradingOrder) arguments[0];
                            setVersion(order, order.getVersion() == null ? 1L : order.getVersion() + 1L);
                            storedOrders.put(order.getId(), order);
                            yield order;
                        }
                        case "count", "countByStatus", "countByStatusIn", "countByTargetStatusAndStatusIn", "countByParentOrderId" -> 0L;
                        default -> throw unsupported(TradingOrderRepository.class, method);
                    }
            );
            ProcessedCommandRepository processed = repository(
                    ProcessedCommandRepository.class,
                    (method, arguments) -> switch (method) {
                        case "findById" -> Optional.ofNullable(processedCommands.get(arguments[0]));
                        case "save" -> {
                            ProcessedCommand entity = (ProcessedCommand) arguments[0];
                            processedCommands.put(entity.result().commandId(), entity);
                            yield entity;
                        }
                        default -> throw unsupported(ProcessedCommandRepository.class, method);
                    }
            );
            OrderEventRepository events = repository(
                    OrderEventRepository.class,
                    (method, arguments) -> switch (method) {
                        case "save" -> {
                            OrderEvent event = (OrderEvent) arguments[0];
                            eventsByCommand.computeIfAbsent(event.domainEvent().commandId(), ignored -> new ArrayList<>()).add(event);
                            yield event;
                        }
                        case "findByCommandIdOrderByOccurredAtAsc" -> List.copyOf(eventsByCommand.getOrDefault(arguments[0], List.of()));
                        default -> throw unsupported(OrderEventRepository.class, method);
                    }
            );
            OrderMetrics metrics = new OrderMetrics(new SimpleMeterRegistry());
            OrderStateCache cache = new OrderStateCache(orders, processed, 1000, 1000);
            AsyncDbWriter asyncDbWriter = new InMemoryAsyncDbWriter(
                    orders, events, processed, storedOrders, eventsByCommand, processedCommands);
            handler = new OrderCommandHandler(orders, events, processed, new ObjectMapper(), ObservationRegistry.NOOP,
                    metrics, cache, asyncDbWriter);
        }

        private ShadowSnapshot replay(OrderInputEvent inputEvent, ObjectMapper objectMapper) {
            try {
                OrderCommand command = objectMapper.readValue(inputEvent.getPayload(), OrderCommand.class);
                ProcessingOutcome outcome = handler.handle(command);
                return new ShadowSnapshot(
                        normalize(outcome.result(), objectMapper),
                        outcome.events().stream().map(event -> OrderShadowComparisonService.normalize(event, objectMapper)).toList()
                );
            } catch (Exception exception) {
                throw new IllegalStateException("Could not shadow-replay command " + inputEvent.getCommandId(), exception);
            }
        }

        private static <T> T repository(Class<T> repositoryType, RepositoryMethod method) {
            Object proxy = Proxy.newProxyInstance(
                    repositoryType.getClassLoader(),
                    new Class<?>[]{repositoryType},
                    (instance, invokedMethod, arguments) -> {
                        if (invokedMethod.getDeclaringClass() == Object.class) {
                            return switch (invokedMethod.getName()) {
                                case "toString" -> repositoryType.getSimpleName() + "ShadowSandbox";
                                case "hashCode" -> System.identityHashCode(instance);
                                case "equals" -> java.util.Objects.equals(instance, arguments[0]);
                                default -> throw unsupported(repositoryType, invokedMethod.getName());
                            };
                        }
                        return method.invoke(invokedMethod.getName(), arguments == null ? new Object[0] : arguments);
                    }
            );
            return repositoryType.cast(proxy);
        }

        private static UnsupportedOperationException unsupported(Class<?> type, String method) {
            return new UnsupportedOperationException(type.getSimpleName() + '.' + method + " is not used by this sandbox");
        }

        @FunctionalInterface
        private interface RepositoryMethod {
            Object invoke(String method, Object[] arguments);
        }
    }

    private static final class InMemoryAsyncDbWriter extends AsyncDbWriter {
        private final Map<UUID, TradingOrder> storedOrders;
        private final Map<UUID, List<OrderEvent>> eventsByCommand;
        private final Map<UUID, ProcessedCommand> processedCommands;

        private InMemoryAsyncDbWriter(TradingOrderRepository orders, OrderEventRepository events,
                                      ProcessedCommandRepository processed,
                                      Map<UUID, TradingOrder> storedOrders,
                                      Map<UUID, List<OrderEvent>> eventsByCommand,
                                      Map<UUID, ProcessedCommand> processedCommands) {
            super(orders, events, processed);
            this.storedOrders = storedOrders;
            this.eventsByCommand = eventsByCommand;
            this.processedCommands = processedCommands;
        }

        @Override
        public void enqueue(TradingOrder order) {
            if (order != null) storedOrders.put(order.getId(), order);
        }

        @Override
        public void enqueue(OrderEvent event) {
            if (event != null) {
                eventsByCommand.computeIfAbsent(event.domainEvent().commandId(), ignored -> new ArrayList<>()).add(event);
            }
        }

        @Override
        public void enqueue(ProcessedCommand command) {
            if (command != null) processedCommands.put(command.result().commandId(), command);
        }

        @Override
        public void flush() {
        }
    }
}
