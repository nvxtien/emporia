package com.emporia.staticdata;

import com.emporia.events.TradingEvents.ListingSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminStaticDataControllerTest {
    private final InstrumentListingRepository repository = mock(InstrumentListingRepository.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private AdminStaticDataController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminStaticDataController(repository, audit);
    }

    @Test
    void searchesListingsWithTrimmedAdminFiltersAndBoundedLimit() {
        InstrumentListing listing = sampleListing(1L, "AAPL", "XNAS", true);
        when(repository.findForAdmin(
                eq("AAPL"),
                eq("XNAS"),
                eq("USD"),
                eq("US"),
                eq(Boolean.TRUE),
                any(Pageable.class))).thenReturn(new PageImpl<>(List.of(listing), PageRequest.of(2, 200), 401));

        AdminInstrumentListingPage result = controller.listings(
                adminJwt(),
                " AAPL ",
                " XNAS ",
                " USD ",
                " US ",
                true,
                2,
                null,
                999);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().symbol()).isEqualTo("AAPL");
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(200);
        assertThat(result.totalElements()).isEqualTo(401);
        assertThat(result.totalPages()).isEqualTo(3);
        verify(repository).findForAdmin(
                "AAPL",
                "XNAS",
                "USD",
                "US",
                true,
                PageRequest.of(2, 200));
    }

    @Test
    void searchesListingsWithEmptyStringFiltersWhenParametersAreMissing() {
        InstrumentListing listing = sampleListing(1L, "AAPL", "XNAS", true);
        when(repository.findForAdmin(
                eq(""),
                eq(""),
                eq(""),
                eq(""),
                eq(null),
                any(Pageable.class))).thenReturn(new PageImpl<>(List.of(listing), PageRequest.of(0, 100), 1));

        AdminInstrumentListingPage result = controller.listings(
                adminJwt(),
                null,
                null,
                null,
                null,
                null,
                -1,
                null,
                100);

        assertThat(result.items()).hasSize(1);
        assertThat(result.page()).isZero();
        verify(repository).findForAdmin(
                "",
                "",
                "",
                "",
                null,
                PageRequest.of(0, 100));
    }

    @Test
    void returnsDisabledListingDetailsForAdmins() {
        InstrumentListing listing = sampleListing(2L, "MSFT", "XNAS", false);
        when(repository.findById(2L)).thenReturn(Optional.of(listing));

        AdminInstrumentListingView result = controller.listing(adminJwt(), 2L);

        assertThat(result.id()).isEqualTo(2L);
        assertThat(result.enabled()).isFalse();
        assertThat(result.referencePrice()).isEqualByComparingTo("100");
    }

    @Test
    void returnsNotFoundForMissingListing() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.listing(adminJwt(), 99L))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void rejectsNonAdminSessions() {
        assertThatThrownBy(() -> controller.listings(userJwt(), null, null, null, null, null, 0, 100, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void returnsStaticDataFacets() {
        when(repository.countByEnabledTrue()).thenReturn(14L);
        when(repository.countByEnabledFalse()).thenReturn(2L);
        when(repository.findExchangeFacetsForAdmin()).thenReturn(List.of(exchangeFacet("XNAS", "Nasdaq", 8)));
        when(repository.findCurrenciesForAdmin()).thenReturn(List.of("USD"));
        when(repository.findCountriesForAdmin()).thenReturn(List.of("US"));

        AdminStaticDataFacets result = controller.facets(adminJwt());

        assertThat(result.totalListings()).isEqualTo(16);
        assertThat(result.enabledListings()).isEqualTo(14);
        assertThat(result.disabledListings()).isEqualTo(2);
        assertThat(result.exchanges()).containsExactly(new AdminExchangeFacet("XNAS", "Nasdaq", 8));
        assertThat(result.currencies()).containsExactly("USD");
        assertThat(result.countries()).containsExactly("US");
    }

    @Test
    void updatesListingAndAuditsTheChange() {
        InstrumentListing listing = sampleListing(7L, "aapl", "XNAS", true);
        when(repository.findById(7L)).thenReturn(Optional.of(listing));

        AdminInstrumentListingView result = controller.updateListing(
                adminJwt(),
                7L,
                new AdminInstrumentListingUpdate(
                        "aapl",
                        "Apple Inc.",
                        "aapl",
                        "xnas",
                        "Nasdaq",
                        "us",
                        "usd",
                        false,
                        new BigDecimal("0.01"),
                        BigDecimal.ONE,
                        new BigDecimal("210.25"),
                        new BigDecimal("209.10")),
                "req-123");

        assertThat(result.id()).isEqualTo(7L);
        assertThat(result.version()).isEqualTo(2);
        assertThat(result.symbol()).isEqualTo("AAPL");
        assertThat(result.marketSymbol()).isEqualTo("AAPL");
        assertThat(result.enabled()).isFalse();
        assertThat(result.referencePrice()).isEqualByComparingTo("210.25");
        verify(audit).record(
                any(AdminAuditContext.class),
                eq("STATIC_DATA_LISTING_UPDATED"),
                eq("INSTRUMENT_LISTING"),
                eq("7"),
                any(AdminInstrumentListingView.class),
                any(AdminInstrumentListingView.class),
                eq(null));
    }

    @Test
    void importsListingsWithBoundedBatchSizeAndAuditsTheBatch() {
        InstrumentListing existing = sampleListing(8L, "MSFT", "XNAS", true);
        when(repository.findById(8L)).thenReturn(Optional.of(existing));
        when(repository.findById(9L)).thenReturn(Optional.empty());

        AdminStaticDataImportResult result = controller.importListings(
                adminJwt(),
                new AdminStaticDataImportRequest(List.of(
                        importRow(8L, "MSFT"),
                        importRow(9L, "NVDA"))),
                "req-456");

        assertThat(result.requested()).isEqualTo(2);
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.listingIds()).containsExactly(8L, 9L);
        assertThat(existing.snapshot().version()).isEqualTo(2);
        verify(repository).save(any(InstrumentListing.class));
        verify(audit).record(
                any(AdminAuditContext.class),
                eq("STATIC_DATA_LISTINGS_IMPORTED"),
                eq("INSTRUMENT_LISTING_IMPORT"),
                eq("batch"),
                eq(null),
                eq(result),
                any(Map.class));
    }

    @Test
    void returnsAuditEventsForAdmins() {
        AdminAuditService.AdminAuditPage page = new AdminAuditService.AdminAuditPage(
                List.of(new AdminAuditService.AdminAuditView(
                        UUID.randomUUID(),
                        Instant.parse("2026-07-30T17:53:31.658Z"),
                        "admin",
                        "admin",
                        "desk",
                        "STATIC_DATA_LISTING_UPDATED",
                        "INSTRUMENT_LISTING",
                        "7",
                        "SUCCESS",
                        "req-123",
                        null,
                        "{}",
                        null)),
                0,
                50,
                1,
                1,
                true,
                true);
        when(audit.list(any(AdminAuditService.AdminAuditFilter.class))).thenReturn(page);

        AdminAuditService.AdminAuditPage result = controller.auditEvents(
                adminJwt(),
                null,
                "STATIC_DATA_LISTING_UPDATED",
                null,
                null,
                null,
                0,
                50);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().action()).isEqualTo("STATIC_DATA_LISTING_UPDATED");
    }

    private static InstrumentListing sampleListing(long id, String symbol, String mic, boolean enabled) {
        ListingSnapshot snapshot = new ListingSnapshot(id, 1, symbol, symbol + " Inc", symbol, mic, "Exchange", "US", "USD",
                new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("99"));
        return new InstrumentListing(snapshot, enabled);
    }

    private static AdminInstrumentListingImportRow importRow(long id, String symbol) {
        return new AdminInstrumentListingImportRow(
                id,
                1,
                symbol,
                symbol + " Inc.",
                symbol,
                "XNAS",
                "Nasdaq",
                "US",
                "USD",
                true,
                new BigDecimal("0.01"),
                BigDecimal.ONE,
                new BigDecimal("100"),
                new BigDecimal("99"));
    }

    private static InstrumentListingRepository.ExchangeFacetProjection exchangeFacet(String mic, String name, long count) {
        return new InstrumentListingRepository.ExchangeFacetProjection() {
            @Override
            public String getMic() {
                return mic;
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public long getListingCount() {
                return count;
            }
        };
    }

    private static Jwt adminJwt() {
        return jwt(List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private static Jwt userJwt() {
        return jwt(List.of("ROLE_USER"));
    }

    private static Jwt jwt(List<String> authorities) {
        Instant now = Instant.parse("2026-07-30T17:53:31.658Z");
        return new Jwt(
                "token",
                now,
                now.plusSeconds(600),
                Map.of("alg", "none"),
                Map.of(
                        "sub", "admin",
                        "authorities", authorities));
    }
}
