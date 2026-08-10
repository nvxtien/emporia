package com.emporia.ordermanagement.controller;

import com.emporia.ordermanagement.disruptor.DisruptorOrderPipeline;
import com.emporia.ordermanagement.service.OrderShadowComparisonService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/internal/hotpath")
class HotPathAdminController {
    private final DisruptorOrderPipeline pipeline;
    private final OrderShadowComparisonService shadows;

    HotPathAdminController(DisruptorOrderPipeline pipeline, OrderShadowComparisonService shadows) {
        this.pipeline = pipeline;
        this.shadows = shadows;
    }

    @GetMapping("/status")
    HotPathStatusView status(@AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        return statusView(pipeline.isAcceptingCommands());
    }

    @PostMapping("/kill-switch")
    HotPathStatusView engage(@AuthenticationPrincipal Jwt jwt,
                             @RequestParam(defaultValue = "manual") String reason) {
        requireAdmin(jwt);
        pipeline.engageKillSwitch(reason);
        return statusView(false);
    }

    @DeleteMapping("/kill-switch")
    HotPathStatusView release(@AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        pipeline.releaseKillSwitch();
        return statusView(true);
    }

    @GetMapping("/shadow-report")
    OrderShadowComparisonService.ShadowComparisonReport shadowReport(@AuthenticationPrincipal Jwt jwt,
                                                                     @RequestParam(defaultValue = "100") int limit,
                                                                     @RequestParam(required = false) Long afterSequenceId) {
        requireAdmin(jwt);
        return shadows.compare(Math.max(1, Math.min(limit, 1000)), afterSequenceId);
    }

    private HotPathStatusView statusView(boolean acceptingCommands) {
        return new HotPathStatusView(acceptingCommands, shadows.latestSequenceId());
    }

    private void requireAdmin(Jwt jwt) {
        if (jwt == null || !authorities(jwt.getClaim("authorities")).contains("ROLE_ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator access required");
        }
    }

    private List<String> authorities(Object claim) {
        if (claim instanceof Collection<?> collection) {
            return collection.stream().filter(Objects::nonNull).map(Object::toString).toList();
        }
        if (claim instanceof String text) {
            return Arrays.stream(text.split("[,\\s]+"))
                    .filter(value -> !value.isBlank())
                    .toList();
        }
        return List.of();
    }

    record HotPathStatusView(boolean acceptingCommands, long latestInputSequenceId) {
    }
}
