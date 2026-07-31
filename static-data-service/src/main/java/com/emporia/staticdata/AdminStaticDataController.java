package com.emporia.staticdata;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.staticdata.AdminAuditService.AdminAuditFilter;
import com.emporia.staticdata.AdminAuditService.AdminAuditPage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/admin/static-data")
class AdminStaticDataController {
    private static final int MAX_IMPORT_ROWS = 200;

    private final InstrumentListingRepository listings;
    private final AdminAuditService audit;

    AdminStaticDataController(InstrumentListingRepository listings, AdminAuditService audit) {
        this.listings = listings;
        this.audit = audit;
    }

    @GetMapping("/listings")
    @Transactional(readOnly = true)
    AdminInstrumentListingPage listings(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String exchangeMic,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String countryCode,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer limit) {
        requireAdmin(jwt);
        Page<InstrumentListing> result = listings.findForAdmin(
                filter(query),
                filter(exchangeMic),
                filter(currency),
                filter(countryCode),
                enabled,
                PageRequest.of(boundedPage(page), boundedSize(size, limit)));
        return AdminInstrumentListingPage.from(result);
    }

    @GetMapping("/listings/{listingId}")
    @Transactional(readOnly = true)
    AdminInstrumentListingView listing(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long listingId) {
        requireAdmin(jwt);
        return listings.findById(listingId)
                .map(AdminInstrumentListingView::from)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                "Static-data listing not found"));
    }

    @PutMapping("/listings/{listingId}")
    @Transactional
    AdminInstrumentListingView updateListing(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long listingId,
            @RequestBody AdminInstrumentListingUpdate request,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        requireAdmin(jwt);
        requireBody(request);
        InstrumentListing listing = listings.findById(listingId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Static-data listing not found"));
        AdminInstrumentListingView before = AdminInstrumentListingView.from(listing);
        ListingSnapshot snapshot = snapshot(listingId, before.version() + 1, request);
        listing.replace(snapshot, enabled(request.enabled()));
        AdminInstrumentListingView after = AdminInstrumentListingView.from(listing);
        audit.record(
                AdminAuditContext.from(jwt, requestId),
                "STATIC_DATA_LISTING_UPDATED",
                "INSTRUMENT_LISTING",
                String.valueOf(listingId),
                before,
                after,
                null);
        return after;
    }

    @PostMapping("/listings/import")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    AdminStaticDataImportResult importListings(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody AdminStaticDataImportRequest request,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        requireAdmin(jwt);
        requireBody(request);
        List<AdminInstrumentListingImportRow> rows = request.listings() == null ? List.of() : request.listings();
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one listing is required");
        }
        if (rows.size() > MAX_IMPORT_ROWS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Static-data imports are limited to 200 listings");
        }

        int created = 0;
        int updated = 0;
        for (AdminInstrumentListingImportRow row : rows) {
            long listingId = positiveId(row.id(), "Listing id is required");
            InstrumentListing existing = listings.findById(listingId).orElse(null);
            int version = existing == null ? positiveVersion(row.version()) : existing.snapshot().version() + 1;
            ListingSnapshot snapshot = snapshot(listingId, version, row);
            if (existing == null) {
                listings.save(new InstrumentListing(snapshot, enabled(row.enabled())));
                created++;
            } else {
                existing.replace(snapshot, enabled(row.enabled()));
                updated++;
            }
        }

        AdminStaticDataImportResult result = new AdminStaticDataImportResult(
                rows.size(),
                created,
                updated,
                rows.stream().map(AdminInstrumentListingImportRow::id).toList());
        audit.record(
                AdminAuditContext.from(jwt, requestId),
                "STATIC_DATA_LISTINGS_IMPORTED",
                "INSTRUMENT_LISTING_IMPORT",
                "batch",
                null,
                result,
                Map.of("maxRows", MAX_IMPORT_ROWS));
        return result;
    }

    @GetMapping("/facets")
    @Transactional(readOnly = true)
    AdminStaticDataFacets facets(@AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        long enabledListings = listings.countByEnabledTrue();
        long disabledListings = listings.countByEnabledFalse();
        return new AdminStaticDataFacets(
                enabledListings + disabledListings,
                enabledListings,
                disabledListings,
                listings.findExchangeFacetsForAdmin().stream()
                        .map(facet -> new AdminExchangeFacet(
                                facet.getMic(),
                                facet.getName(),
                                facet.getListingCount()))
                        .toList(),
                listings.findCurrenciesForAdmin(),
                listings.findCountriesForAdmin());
    }

    @GetMapping("/audit/events")
    @Transactional(readOnly = true)
    AdminAuditPage auditEvents(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String result,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        requireAdmin(jwt);
        return audit.list(new AdminAuditFilter(actor, action, entityType, entityId, result, page, size));
    }

    private void requireAdmin(Jwt jwt) {
        if (jwt == null || !authorities(jwt.getClaim("authorities")).contains("ROLE_ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator access required");
        }
    }

    private List<String> authorities(Object claim) {
        if (claim instanceof Collection<?> collection) {
            return collection.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .toList();
        }
        if (claim instanceof String text) {
            return Arrays.stream(text.split("[,\\s]+"))
                    .filter(value -> !value.isBlank())
                    .toList();
        }
        return List.of();
    }

    private String filter(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    private int boundedPage(int page) {
        return Math.max(0, page);
    }

    private int boundedSize(Integer size, Integer limit) {
        int requestedSize = size == null ? (limit == null ? 100 : limit) : size;
        return Math.max(1, Math.min(200, requestedSize));
    }

    private ListingSnapshot snapshot(long listingId, int version, AdminInstrumentListingFields fields) {
        return new ListingSnapshot(
                listingId,
                version,
                symbol(fields.symbol()),
                text(fields.name(), "Name is required", 200),
                text(fields.marketSymbol(), "Market symbol is required", 24).toUpperCase(Locale.ROOT),
                text(fields.exchangeMic(), "Exchange MIC is required", 12).toUpperCase(Locale.ROOT),
                text(fields.exchangeName(), "Exchange name is required", 120),
                code(fields.countryCode(), "Country code is required", 2),
                code(fields.currency(), "Currency is required", 3),
                positiveDecimal(fields.tickSize(), "Tick size must be positive"),
                positiveDecimal(fields.sizeIncrement(), "Size increment must be positive"),
                nonNegativeDecimal(fields.referencePrice(), "Reference price must be non-negative"),
                nonNegativeDecimal(fields.previousClose(), "Previous close must be non-negative"));
    }

    private static void requireBody(Object request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required");
        }
    }

    private static boolean enabled(Boolean value) {
        return value == null || value;
    }

    private static long positiveId(Long value, String message) {
        if (value == null || value <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return value;
    }

    private static int positiveVersion(Integer value) {
        return value == null || value <= 0 ? 1 : value;
    }

    private static String symbol(String value) {
        String result = text(value, "Symbol is required", 24).toUpperCase(Locale.ROOT);
        if (!result.matches("[A-Z0-9.\\-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Symbol contains unsupported characters");
        }
        return result;
    }

    private static String code(String value, String message, int length) {
        String result = text(value, message, length).toUpperCase(Locale.ROOT);
        if (result.length() != length) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return result;
    }

    private static String text(String value, String message, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        String result = value.strip();
        if (result.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return result;
    }

    private static BigDecimal positiveDecimal(BigDecimal value, String message) {
        if (value == null || value.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return value;
    }

    private static BigDecimal nonNegativeDecimal(BigDecimal value, String message) {
        if (value == null || value.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return value;
    }
}

interface AdminInstrumentListingFields {
    String symbol();
    String name();
    String marketSymbol();
    String exchangeMic();
    String exchangeName();
    String countryCode();
    String currency();
    Boolean enabled();
    BigDecimal tickSize();
    BigDecimal sizeIncrement();
    BigDecimal referencePrice();
    BigDecimal previousClose();
}

record AdminInstrumentListingUpdate(
        String symbol,
        String name,
        String marketSymbol,
        String exchangeMic,
        String exchangeName,
        String countryCode,
        String currency,
        Boolean enabled,
        BigDecimal tickSize,
        BigDecimal sizeIncrement,
        BigDecimal referencePrice,
        BigDecimal previousClose) implements AdminInstrumentListingFields {
}

record AdminStaticDataImportRequest(List<AdminInstrumentListingImportRow> listings) {
}

record AdminInstrumentListingImportRow(
        Long id,
        Integer version,
        String symbol,
        String name,
        String marketSymbol,
        String exchangeMic,
        String exchangeName,
        String countryCode,
        String currency,
        Boolean enabled,
        BigDecimal tickSize,
        BigDecimal sizeIncrement,
        BigDecimal referencePrice,
        BigDecimal previousClose) implements AdminInstrumentListingFields {
}

record AdminStaticDataImportResult(int requested, int created, int updated, List<Long> listingIds) {
}

record AdminInstrumentListingPage(
        List<AdminInstrumentListingView> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    static AdminInstrumentListingPage from(Page<InstrumentListing> page) {
        return new AdminInstrumentListingPage(
                page.getContent().stream()
                        .map(AdminInstrumentListingView::from)
                        .toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }
}

record AdminInstrumentListingView(
        long id,
        int version,
        String symbol,
        String name,
        String marketSymbol,
        String exchangeMic,
        String exchangeName,
        String countryCode,
        String currency,
        boolean enabled,
        BigDecimal tickSize,
        BigDecimal sizeIncrement,
        BigDecimal referencePrice,
        BigDecimal previousClose) {

    static AdminInstrumentListingView from(InstrumentListing listing) {
        ListingSnapshot snapshot = listing.snapshot();
        return new AdminInstrumentListingView(
                snapshot.id(),
                snapshot.version(),
                snapshot.symbol(),
                snapshot.name(),
                snapshot.marketSymbol(),
                snapshot.exchangeMic(),
                snapshot.exchangeName(),
                snapshot.countryCode(),
                snapshot.currency(),
                listing.isEnabled(),
                snapshot.tickSize(),
                snapshot.sizeIncrement(),
                snapshot.referencePrice(),
                snapshot.previousClose());
    }
}

record AdminStaticDataFacets(
        long totalListings,
        long enabledListings,
        long disabledListings,
        List<AdminExchangeFacet> exchanges,
        List<String> currencies,
        List<String> countries) {
}

record AdminExchangeFacet(String mic, String name, long listingCount) {
}
