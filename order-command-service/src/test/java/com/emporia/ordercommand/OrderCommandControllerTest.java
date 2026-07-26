package com.emporia.ordercommand;

import com.emporia.events.TradingEvents.CancelAllView;
import com.emporia.events.TradingEvents.CommandType;
import com.emporia.events.TradingEvents.OrderCommand;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderCommandControllerTest {

    @Test
    void cancelAllPublishesAUserScopedCommand() {
        StaticDataClient staticData = mock(StaticDataClient.class);
        KafkaCommandGateway commands = mock(KafkaCommandGateway.class);
        when(commands.send(any(OrderCommand.class))).thenReturn("{\"cancelled\":3}");
        OrderCommandController controller = new OrderCommandController(staticData, commands, new ObjectMapper());
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("user-123")
                .claim("desk", "desk-7").claim("can_trade", true).build();

        CancelAllView result = controller.cancelAll(jwt);

        assertThat(result.cancelled()).isEqualTo(3);
        ArgumentCaptor<OrderCommand> command = ArgumentCaptor.forClass(OrderCommand.class);
        verify(commands).send(command.capture());
        assertThat(command.getValue().commandType()).isEqualTo(CommandType.CANCEL_ALL);
        assertThat(command.getValue().userSubject()).isEqualTo("user-123");
        assertThat(command.getValue().deskId()).isEqualTo("desk-7");
        assertThat(command.getValue().orderId()).isNull();
        assertThat(command.getValue().executionParameters()).isEmpty();
    }

    @Test
    void viewOnlyUsersCannotPublishTradingCommands() {
        StaticDataClient staticData = mock(StaticDataClient.class);
        KafkaCommandGateway commands = mock(KafkaCommandGateway.class);
        OrderCommandController controller = new OrderCommandController(staticData, commands, new ObjectMapper());
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("viewer")
                .claim("desk", "desk-7").claim("can_trade", false).build();

        assertThatThrownBy(() -> controller.cancelAll(jwt))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
    }
}
