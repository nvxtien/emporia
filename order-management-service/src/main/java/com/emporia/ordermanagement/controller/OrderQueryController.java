package com.emporia.ordermanagement.controller;

import com.emporia.events.TradingEvents.OrderView;
import com.emporia.ordermanagement.dto.AdminExecutionView;
import com.emporia.ordermanagement.dto.ExecutionStrategyView;
import com.emporia.ordermanagement.dto.ExecutionView;
import com.emporia.ordermanagement.dto.OrderEventView;
import com.emporia.ordermanagement.model.TradingOrder;
import com.emporia.ordermanagement.repository.ExecutionRepository;
import com.emporia.ordermanagement.repository.OrderEventRepository;
import com.emporia.ordermanagement.repository.TradingOrderRepository;
import com.emporia.ordermanagement.service.OrderStreamService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/orders")
public class OrderQueryController {
    private final TradingOrderRepository orders;
    private final OrderEventRepository events;
    private final ExecutionRepository executions;
    private final OrderStreamService streams;

    OrderQueryController(TradingOrderRepository orders,
            OrderEventRepository events,
            ExecutionRepository executions,
            OrderStreamService streams) {
        this.orders = orders;
        this.events = events;
        this.executions = executions;
        this.streams = streams;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<OrderView> orders(@AuthenticationPrincipal Jwt jwt) {
        return orders.findByDeskIdOrderByCreatedAtDesc(desk(jwt)).stream().map(TradingOrder::view).toList();
    }

    @GetMapping("/executions")
    @Transactional(readOnly = true)
    public List<AdminExecutionView> recentExecutions(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String deskId,
            @RequestParam(required = false) String venue,
            @RequestParam(required = false) String destination,
            @RequestParam(defaultValue = "100") int limit) {
        requireAdmin(jwt);
        return executions.findRecentForAdmin(
                        filter(deskId),
                        filter(venue),
                        filter(destination),
                        PageRequest.of(0, boundedLimit(limit)))
                .stream()
                .map(AdminExecutionView::from)
                .toList();
    }

    @GetMapping("/strategies")
    @Transactional(readOnly = true)
    public List<ExecutionStrategyView> strategies(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String deskId,
            @RequestParam(defaultValue = "100") int limit) {
        requireAdmin(jwt);
        String deskFilter = filter(deskId);
        List<String> strategyDestinations = List.of("SMART", "VWAP");
        List<TradingOrder> parentOrders = deskFilter == null
                ? orders.findByParentOrderIdIsNullAndDestinationInOrderByUpdatedAtDesc(
                        strategyDestinations, PageRequest.of(0, boundedLimit(limit)))
                : orders.findByDeskIdAndParentOrderIdIsNullAndDestinationInOrderByUpdatedAtDesc(
                        deskFilter, strategyDestinations, PageRequest.of(0, boundedLimit(limit)));
        return parentOrders.stream()
                .map(order -> ExecutionStrategyView.from(order, orders.countByParentOrderId(order.getId())))
                .toList();
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Transactional(readOnly = true)
    public SseEmitter stream(@AuthenticationPrincipal Jwt jwt) {
        String desk = desk(jwt);
        return streams.subscribe(desk,
                orders.findByDeskIdOrderByCreatedAtDesc(desk).stream().map(TradingOrder::view).toList());
    }

    @GetMapping("/{orderId}")
    @Transactional(readOnly = true)
    public OrderView order(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        return findOnDesk(desk(jwt), orderId).view();
    }

    @GetMapping("/{orderId}/history")
    @Transactional(readOnly = true)
    public List<OrderEventView> history(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        findOnDesk(desk(jwt), orderId);
        return events.findByOrderIdOrderByOccurredAtAsc(orderId).stream().map(OrderEventView::from).toList();
    }

    @GetMapping("/{orderId}/executions")
    @Transactional(readOnly = true)
    public List<ExecutionView> executions(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        findOnDesk(desk(jwt), orderId);
        return executions.findByOrderIdOrderByExecutedAtAsc(orderId).stream().map(ExecutionView::from).toList();
    }

    private TradingOrder findOnDesk(String desk, UUID orderId) {
        return orders.findByIdAndDeskId(orderId, desk)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
    }

    private void requireAdmin(Jwt jwt) {
        if (jwt == null || !authorities(jwt.getClaim("authorities")).contains("ROLE_ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator access required");
        }
    }

    private List<String> authorities(Object claim) {
        if (claim instanceof Collection<?> collection) {
            return collection.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .toList();
        }
        if (claim instanceof String text) {
            return Arrays.stream(text.split("[,\\s]+"))
                    .filter(value -> !value.isBlank())
                    .toList();
        }
        return List.of();
    }

    private String filter(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private int boundedLimit(int limit) {
        return Math.max(1, Math.min(200, limit));
    }

    private String desk(Jwt jwt) {
        String desk = jwt.getClaimAsString("desk");
        return desk == null || desk.isBlank() ? jwt.getSubject() : desk;
    }
}
