package com.emporia.gateway.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
class OrdersFallbackController {
    private final Counter circuitOpen;

    OrdersFallbackController(MeterRegistry meters) {
        this.circuitOpen = meters.counter("emporia.gateway.orders.circuit_open");
    }

    @RequestMapping(path = "/fallback/orders", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> ordersFallback() {
        circuitOpen.increment();
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("X-Fallback-Reason", "gateway-order-circuit-open")
                .body(Map.of(
                        "code", "gateway_order_circuit_open",
                        "message", "Order gateway temporarily unavailable; retry later"
                ));
    }
}