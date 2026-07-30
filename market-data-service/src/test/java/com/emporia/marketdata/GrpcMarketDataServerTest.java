package com.emporia.marketdata;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GrpcMarketDataServerTest {
    private final MarketDataStreamService streams = mock(MarketDataStreamService.class);
    private final ServiceAccessTokenProvider tokens = mock(ServiceAccessTokenProvider.class);
    private final GrpcMarketDataService service = new GrpcMarketDataService(streams, tokens);

    @Test
    void disabledServerDoesNotStart() {
        GrpcMarketDataServer server = new GrpcMarketDataServer(service, false, 0);
        server.start();
        assertThat(server.isRunning()).isFalse();
    }

    @Test
    void startsAndStopsOnRandomPort() {
        GrpcMarketDataServer server = new GrpcMarketDataServer(service, true, 0);
        server.start();
        assertThat(server.isRunning()).isTrue();
        assertThat(server.getPhase()).isEqualTo(Integer.MAX_VALUE - 50);

        server.stop();
        assertThat(server.isRunning()).isFalse();
    }
}
