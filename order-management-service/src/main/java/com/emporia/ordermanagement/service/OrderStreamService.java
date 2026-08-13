package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.events.TradingEvents.OrderView;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class OrderStreamService {
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Subscription>> subscriptions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    OrderStreamService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SseEmitter subscribe(String deskId, List<OrderView> initialOrders) {
        SseEmitter emitter = newEmitter();
        Subscription subscription = new Subscription(UUID.randomUUID(), deskId, emitter);
        subscriptions.computeIfAbsent(deskId, ignored -> new CopyOnWriteArrayList<>()).add(subscription);
        emitter.onCompletion(() -> remove(subscription));
        emitter.onTimeout(() -> remove(subscription));
        emitter.onError(ignored -> remove(subscription));
        try {
            sendHeartbeat(emitter);
            for (OrderView order : initialOrders) {
                send(emitter, order.id() + ":" + order.version(), order);
            }
        } catch (Exception exception) {
            remove(subscription);
            completeWithErrorQuietly(emitter, exception);
        }
        return emitter;
    }

    public void publish(OrderDomainEvent event) {
        String deskId = event.deskId();
        if (deskId == null || deskId.isBlank()) deskId = event.userSubject();
        if (deskId == null || deskId.isBlank()) return;
        List<Subscription> deskSubscriptions = subscriptions.getOrDefault(
                deskId, new CopyOnWriteArrayList<>());
        if (deskSubscriptions.isEmpty()) return;
        Object order;
        try {
            order = objectMapper.readTree(event.payload());
        } catch (Exception exception) {
            return;
        }
        for (Subscription subscription : deskSubscriptions) {
            try {
                send(subscription.emitter(), event.eventId().toString(), order);
            } catch (Exception disconnected) {
                remove(subscription);
                completeQuietly(subscription.emitter());
            }
        }
    }

    @Scheduled(fixedDelayString = "${emporia.orders.stream.heartbeat-interval-ms:5000}")
    void heartbeat() {
        subscriptions.forEach((ignoredDeskId, deskSubscriptions) -> {
            for (Subscription subscription : deskSubscriptions) {
                try {
                    sendHeartbeat(subscription.emitter());
                } catch (Exception disconnected) {
                    remove(subscription);
                    completeQuietly(subscription.emitter());
                }
            }
        });
    }

    SseEmitter newEmitter() {
        return new SseEmitter(0L);
    }

    private void send(SseEmitter emitter, String id, Object order) throws Exception {
        emitter.send(SseEmitter.event().id(id).name("order").reconnectTime(1_000).data(order));
    }

    private void sendHeartbeat(SseEmitter emitter) throws Exception {
        emitter.send(SseEmitter.event().name("heartbeat").reconnectTime(1_000).data("ok"));
    }

    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
        }
    }

    private void completeWithErrorQuietly(SseEmitter emitter, Exception exception) {
        try {
            emitter.completeWithError(exception);
        } catch (IllegalStateException ignored) {
        }
    }

    private void remove(Subscription subscription) {
        CopyOnWriteArrayList<Subscription> deskSubscriptions = subscriptions.get(subscription.deskId());
        if (deskSubscriptions == null) return;
        deskSubscriptions.remove(subscription);
        if (deskSubscriptions.isEmpty()) subscriptions.remove(subscription.deskId(), deskSubscriptions);
    }

    private record Subscription(UUID id, String deskId, SseEmitter emitter) {
    }
}
