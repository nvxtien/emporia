package com.emporia.ordermanagement.controller;

import com.emporia.ordermanagement.disruptor.DisruptorOrderPipeline;
import com.emporia.ordermanagement.service.OrderShadowComparisonService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HotPathAdminControllerTest {
    private final DisruptorOrderPipeline pipeline = mock(DisruptorOrderPipeline.class);
    private final OrderShadowComparisonService shadows = mock(OrderShadowComparisonService.class);
    private final HotPathAdminController controller = new HotPathAdminController(pipeline, shadows);

    @Test
    void adminCanEngageAndReleaseKillSwitch() {
        when(pipeline.isAcceptingCommands()).thenReturn(true, false, true);

        assertThat(controller.status(adminJwt()).acceptingCommands()).isTrue();
        assertThat(controller.engage(adminJwt(), "drill").acceptingCommands()).isFalse();
        assertThat(controller.release(adminJwt()).acceptingCommands()).isTrue();

        verify(pipeline).engageKillSwitch("drill");
        verify(pipeline).releaseKillSwitch();
    }

    @Test
    void adminCanRequestShadowReport() {
        OrderShadowComparisonService.ShadowComparisonReport report =
                new OrderShadowComparisonService.ShadowComparisonReport(1, 1, 0, 1.0d, List.of());
        when(shadows.compare(100)).thenReturn(report);

        assertThat(controller.shadowReport(adminJwt(), 100)).isEqualTo(report);
    }

    @Test
    void nonAdminIsRejected() {
        assertThatThrownBy(() -> controller.status(userJwt()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Administrator access required");
    }

    private static Jwt adminJwt() {
        return Jwt.withTokenValue("mock-token")
                .header("alg", "none")
                .subject("admin")
                .claim("authorities", List.of("ROLE_USER", "ROLE_ADMIN"))
                .build();
    }

    private static Jwt userJwt() {
        return Jwt.withTokenValue("mock-token")
                .header("alg", "none")
                .subject("user")
                .claim("authorities", List.of("ROLE_USER"))
                .build();
    }
}