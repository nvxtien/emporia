package com.emporia.ordermanagement.service;

import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.events.TradingEvents.OrderView;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class OrderStreamService {
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Subscription>> subscriptions =
            new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    OrderStreamService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SseEmitter subscribe(String deskId, List<OrderView> initialOrders) {
        SseEmitter emitter = new SseEmitter(0L);
        Subscription subscription = new Subscription(UUID.randomUUID(), deskId, emitter);
        subscriptions.computeIfAbsent(deskId, ignored -> new CopyOnWriteArrayList<>()).add(subscription);
        emitter.onCompletion(() -> remove(subscription));
        emitter.onTimeout(() -> remove(subscription));
        emitter.onError(ignored -> remove(subscription));
        try {
            for (OrderView order : initialOrders) {
                send(emitter, order.id() + ":" + order.version(), order);
            }
        } catch (Exception exception) {
            remove(subscription);
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    void publish(OrderDomainEvent event) {
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
                subscription.emitter().complete();
            }
        }
    }

    private void send(SseEmitter emitter, String id, Object order) throws Exception {
        emitter.send(SseEmitter.event().id(id).name("order").reconnectTime(1_000).data(order));
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
