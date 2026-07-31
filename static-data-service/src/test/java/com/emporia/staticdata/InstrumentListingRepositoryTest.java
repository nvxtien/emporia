package com.emporia.staticdata;

import com.emporia.events.TradingEvents.ListingSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:static_data_repository;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS emporia_static_data",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.default-schema=emporia_static_data",
        "spring.flyway.schemas=emporia_static_data",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.default_schema=emporia_static_data"
})
@Transactional
class InstrumentListingRepositoryTest {

    @Autowired
    private InstrumentListingRepository repository;

    @Test
    void adminSearchIncludesDisabledListingsAndFacetQueries() {
        InstrumentListing disabled = sampleListing(9001L, "TEST", "XNAS", false);
        repository.saveAndFlush(disabled);

        Page<InstrumentListing> filtered = repository.findForAdmin(
                "TEST",
                "XNAS",
                "USD",
                "US",
                false,
                PageRequest.of(0, 10));
        assertThat(filtered.getContent())
                .extracting(listing -> listing.snapshot().id())
                .containsExactly(9001L);
        assertThat(filtered.getTotalElements()).isEqualTo(1);

        Page<InstrumentListing> unfiltered = repository.findForAdmin(
                "",
                "",
                "",
                "",
                null,
                PageRequest.of(0, 10));
        assertThat(unfiltered.getContent()).isNotEmpty();
        assertThat(unfiltered.getTotalElements()).isGreaterThan(unfiltered.getContent().size());
        assertThat(repository.countByEnabledFalse()).isGreaterThanOrEqualTo(1);
        assertThat(repository.findCurrenciesForAdmin()).contains("USD");
        assertThat(repository.findCountriesForAdmin()).contains("US");
        assertThat(repository.findExchangeFacetsForAdmin())
                .anySatisfy(exchange -> {
                    assertThat(exchange.getMic()).isEqualTo("XNAS");
                    assertThat(exchange.getName()).isNotBlank();
                    assertThat(exchange.getListingCount()).isGreaterThan(0);
                });
    }

    private static InstrumentListing sampleListing(long id, String symbol, String mic, boolean enabled) {
        ListingSnapshot snapshot = new ListingSnapshot(id, 1, symbol, symbol + " Inc", symbol, mic, "Exchange", "US", "USD",
                new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("99"));
        return new InstrumentListing(snapshot, enabled);
    }
}
