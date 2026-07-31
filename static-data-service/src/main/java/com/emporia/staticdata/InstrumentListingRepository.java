package com.emporia.staticdata;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    @Query(value = """
            select listing from InstrumentListing listing
            where (:query = ''
                   or lower(listing.symbol) like lower(concat('%', :query, '%'))
                   or lower(listing.name) like lower(concat('%', :query, '%'))
                   or lower(listing.marketSymbol) like lower(concat('%', :query, '%'))
                   or lower(listing.exchangeMic) like lower(concat('%', :query, '%')))
              and (:exchangeMic = '' or lower(listing.exchangeMic) = lower(:exchangeMic))
              and (:currency = '' or lower(listing.currency) = lower(:currency))
              and (:countryCode = '' or lower(listing.countryCode) = lower(:countryCode))
              and (:enabled is null or listing.enabled = :enabled)
            order by listing.symbol, listing.exchangeMic, listing.id
            """,
            countQuery = """
            select count(listing) from InstrumentListing listing
            where (:query = ''
                   or lower(listing.symbol) like lower(concat('%', :query, '%'))
                   or lower(listing.name) like lower(concat('%', :query, '%'))
                   or lower(listing.marketSymbol) like lower(concat('%', :query, '%'))
                   or lower(listing.exchangeMic) like lower(concat('%', :query, '%')))
              and (:exchangeMic = '' or lower(listing.exchangeMic) = lower(:exchangeMic))
              and (:currency = '' or lower(listing.currency) = lower(:currency))
              and (:countryCode = '' or lower(listing.countryCode) = lower(:countryCode))
              and (:enabled is null or listing.enabled = :enabled)
            """)
    Page<InstrumentListing> findForAdmin(
            @Param("query") String query,
            @Param("exchangeMic") String exchangeMic,
            @Param("currency") String currency,
            @Param("countryCode") String countryCode,
            @Param("enabled") Boolean enabled,
            Pageable pageable);

    long countByEnabledTrue();

    long countByEnabledFalse();

    @Query("""
            select listing.exchangeMic as mic,
                   min(listing.exchangeName) as name,
                   count(listing) as listingCount
            from InstrumentListing listing
            group by listing.exchangeMic
            order by listing.exchangeMic
            """)
    List<ExchangeFacetProjection> findExchangeFacetsForAdmin();

    @Query("select distinct listing.currency from InstrumentListing listing order by listing.currency")
    List<String> findCurrenciesForAdmin();

    @Query("select distinct listing.countryCode from InstrumentListing listing order by listing.countryCode")
    List<String> findCountriesForAdmin();

    interface ExchangeFacetProjection {
        String getMic();
        String getName();
        long getListingCount();
    }
}
