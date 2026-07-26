package com.emporia.marketdata;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketDataControllerTest {
    private final MarketDataService service = mock(MarketDataService.class);
    private final MarketDataStreamService streams = mock(MarketDataStreamService.class);
    private final MarketDataController controller = new MarketDataController(service, streams);

    @Test
    void removesDuplicateListingIdsAndCapsQuoteBatchesAtFifty() {
        List<Long> ids = new ArrayList<>();
        ids.add(1L);
        ids.add(1L);
        for (long id = 2; id <= 55; id++) {
            ids.add(id);
        }
        when(service.getQuotes(anyList(), eq("Bearer token"))).thenReturn(List.of());

        controller.quotes(ids, "Bearer token");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> forwardedIds = ArgumentCaptor.forClass(List.class);
        verify(service).getQuotes(forwardedIds.capture(), eq("Bearer token"));
        assertThat(forwardedIds.getValue()).hasSize(50).doesNotHaveDuplicates();
        assertThat(forwardedIds.getValue()).startsWith(1L, 2L, 3L).endsWith(50L);
    }

    @Test
    void forwardsDepthRequestsWithTheirAuthorizationHeader() {
        controller.depth(42, "Bearer token");

        verify(service).getQuote(42, "Bearer token");
    }

    @Test
    void opensAConflatedStreamForDistinctListingIds() {
        when(streams.subscribe(anyList(), eq("Bearer token"), any()))
                .thenReturn(mock(ConflatedQuoteSubscription.class));

        controller.stream(List.of(1L, 1L, 2L), "Bearer token").complete();

        verify(streams).subscribe(eq(List.of(1L, 2L)), eq("Bearer token"), any());
    }
}
