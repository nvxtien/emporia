package com.emporia.staticdata;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

interface InstrumentListingRepository extends JpaRepository<InstrumentListing, Long> {
    @Query("""
            select listing from InstrumentListing listing
            where listing.enabled = true
              and (:query = ''
                   or lower(listing.symbol) like lower(concat('%', :query, '%'))
                   or lower(listing.name) like lower(concat('%', :query, '%'))
                   or lower(listing.exchangeMic) like lower(concat('%', :query, '%')))
            order by case when lower(listing.symbol) = lower(:query) then 0 else 1 end, listing.symbol
            """)
    List<InstrumentListing> search(@Param("query") String query);

    List<InstrumentListing> findByIdInAndEnabledTrue(Collection<Long> ids);

    List<InstrumentListing> findBySymbolInAndEnabledTrueOrderById(Collection<String> symbols);

    List<InstrumentListing> findBySymbolIgnoreCaseAndEnabledTrueOrderById(String symbol);

    java.util.Optional<InstrumentListing> findFirstByMarketSymbolIgnoreCaseAndExchangeMicIgnoreCaseAndEnabledTrue(
            String marketSymbol, String exchangeMic);

    @Query("""
            select listing from InstrumentListing listing
            where listing.enabled = true and lower(listing.symbol) like lower(concat(:prefix, '%'))
            order by listing.symbol, listing.exchangeMic
            """)
    List<InstrumentListing> findBySymbolPrefix(@Param("prefix") String prefix);
}
