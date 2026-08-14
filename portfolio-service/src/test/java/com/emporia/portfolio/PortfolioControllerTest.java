package com.emporia.portfolio;

import com.emporia.portfolio.PortfolioContracts.Balance;
import com.emporia.portfolio.PortfolioContracts.RiskSeed;
import com.emporia.portfolio.PortfolioContracts.Snapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortfolioControllerTest {
    private final PortfolioReceiptService service = mock(PortfolioReceiptService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private PortfolioController controller;

    @BeforeEach
    void setUp() {
        controller = new PortfolioController(service, objectMapper);
    }

    @Test
    void loadReturnsRiskSeed() {
        RiskSeed seed = new RiskSeed(1, 100L, 50L, List.of(new Balance(1, 1000L)));
        when(service.load(100L)).thenReturn(seed);

        RiskSeed result = controller.load(100L);
        assertThat(result).isNotNull();
        assertThat(result.clientId()).isEqualTo(100L);
    }

    @Test
    void publishValidPayloadSuccess() throws Exception {
        Snapshot snapshot = new Snapshot(1, "1", 10L, 100L, "SETTLED", List.of(new Balance(1, 1000L)));
        byte[] payload = objectMapper.writeValueAsBytes(snapshot);

        ResponseEntity<Void> response = controller.publish(10L, 100L, "evt-1", payload);
        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(service).apply(eq(10L), eq(100L), eq("evt-1"), eq(payload), any(Snapshot.class));
    }

    @Test
    void publishInvalidJsonThrowsContractException() {
        byte[] invalidJson = "BAD_JSON".getBytes();
        assertThatThrownBy(() -> controller.publish(10L, 100L, "evt-1", invalidJson))
                .isInstanceOf(PortfolioContractException.class)
                .hasMessageContaining("snapshot body is not valid JSON");
    }
}
