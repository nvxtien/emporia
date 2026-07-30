package com.emporia.staticdata;

import com.emporia.events.TradingEvents.ListingSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InstrumentControllerTest {
    private final InstrumentListingRepository repository = mock(InstrumentListingRepository.class);
    private InstrumentController controller;

    @BeforeEach
    void setUp() {
        controller = new InstrumentController(repository);
    }

    @Test
    void searchReturnsListings() {
        InstrumentListing listing = sampleListing(1L, "AAPL", "XNAS", true);
        when(repository.search("AAPL")).thenReturn(List.of(listing));

        List<ListingSnapshot> result = controller.search(" AAPL ");
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().symbol()).isEqualTo("AAPL");
    }

    @Test
    void getListingByIdSuccess() {
        InstrumentListing listing = sampleListing(1L, "AAPL", "XNAS", true);
        when(repository.findById(1L)).thenReturn(Optional.of(listing));

        ListingSnapshot snapshot = controller.get(1L);
        assertThat(snapshot.id()).isEqualTo(1L);
    }

    @Test
    void getListingByIdNotFoundOrDisabled() {
        when(repository.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.get(1L))
                .isInstanceOf(InstrumentController.InstrumentNotFoundException.class);

        InstrumentListing disabled = sampleListing(2L, "MSFT", "XNAS", false);
        when(repository.findById(2L)).thenReturn(Optional.of(disabled));
        assertThatThrownBy(() -> controller.get(2L))
                .isInstanceOf(InstrumentController.InstrumentNotFoundException.class);
    }

    @Test
    void batchListingsPreservesRequestedOrder() {
        InstrumentListing first = sampleListing(1L, "AAPL", "XNAS", true);
        InstrumentListing second = sampleListing(2L, "MSFT", "XNAS", true);
        when(repository.findByIdInAndEnabledTrue(List.of(2L, 1L))).thenReturn(List.of(first, second));

        List<ListingSnapshot> result = controller.batch(List.of(2L, 1L));
        assertThat(result).extracting(ListingSnapshot::id).containsExactly(2L, 1L);
    }

    @Test
    void bySymbolsReturnsListings() {
        InstrumentListing listing = sampleListing(1L, "AAPL", "XNAS", true);
        when(repository.findBySymbolInAndEnabledTrueOrderById(List.of("AAPL"))).thenReturn(List.of(listing));

        List<ListingSnapshot> result = controller.bySymbols(List.of("AAPL"));
        assertThat(result).hasSize(1);
    }

    @Test
    void sameInstrumentReturnsListings() {
        InstrumentListing listing = sampleListing(1L, "AAPL", "XNAS", true);
        when(repository.findById(1L)).thenReturn(Optional.of(listing));
        when(repository.findBySymbolIgnoreCaseAndEnabledTrueOrderById("AAPL")).thenReturn(List.of(listing));

        List<ListingSnapshot> result = controller.sameInstrument(1L);
        assertThat(result).hasSize(1);
    }

    @Test
    void sameInstrumentNotFound() {
        when(repository.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.sameInstrument(1L))
                .isInstanceOf(InstrumentController.InstrumentNotFoundException.class);
    }

    @Test
    void matchReturnsListing() {
        InstrumentListing listing = sampleListing(1L, "AAPL", "XNAS", true);
        when(repository.findFirstByMarketSymbolIgnoreCaseAndExchangeMicIgnoreCaseAndEnabledTrue("AAPL", "XNAS"))
                .thenReturn(Optional.of(listing));

        ListingSnapshot snapshot = controller.match(" AAPL ", " XNAS ");
        assertThat(snapshot.symbol()).isEqualTo("AAPL");
    }

    @Test
    void matchNotFound() {
        when(repository.findFirstByMarketSymbolIgnoreCaseAndExchangeMicIgnoreCaseAndEnabledTrue("AAPL", "XNAS"))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.match("AAPL", "XNAS"))
                .isInstanceOf(InstrumentController.InstrumentNotFoundException.class);
    }

    @Test
    void matchingReturnsListings() {
        InstrumentListing listing = sampleListing(1L, "AAPL", "XNAS", true);
        when(repository.findBySymbolPrefix("AA")).thenReturn(List.of(listing));

        List<ListingSnapshot> result = controller.matching(" AA ");
        assertThat(result).hasSize(1);
    }

    private static InstrumentListing sampleListing(long id, String symbol, String mic, boolean enabled) {
        ListingSnapshot snapshot = new ListingSnapshot(id, 1, symbol, symbol + " Inc", symbol, mic, "Exchange", "US", "USD",
                new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100"));
        return new InstrumentListing(snapshot, enabled);
    }
}
