package com.emporia.staticdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Current replacement for the three legacy example loaders. Alpaca's asset
 * master supplies instruments and primary US exchange listings; an XOSR
 * composite listing is generated for each symbol. Existing normalized
 * OpenTP tables remain supported by {@link LegacyReferenceDataImporter}.
 */
@Component
@ConditionalOnProperty(name = "emporia.static-data.alpaca-import-enabled", havingValue = "true")
class AlpacaReferenceDataImporter implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AlpacaReferenceDataImporter.class);
    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

    private final JdbcTemplate jdbc;
    private final RestClient alpaca;
    private final String targetSchema;
    private final BigDecimal defaultReferencePrice;

    AlpacaReferenceDataImporter(
            JdbcTemplate jdbc,
            RestClient.Builder builder,
            @Value("${emporia.static-data.alpaca-api-endpoint}") String endpoint,
            @Value("${emporia.static-data.alpaca-api-key-id}") String apiKey,
            @Value("${emporia.static-data.alpaca-api-secret-key}") String apiSecret,
            @Value("${spring.flyway.default-schema:emporia_static_data}") String targetSchema,
            @Value("${emporia.static-data.alpaca-default-reference-price:100}") BigDecimal defaultReferencePrice) {
        if (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.isBlank()) {
            throw new IllegalArgumentException(
                    "Alpaca reference-data import requires APCA_API_KEY_ID and APCA_API_SECRET_KEY");
        }
        this.jdbc = jdbc;
        // Injected, not RestClient.builder(): only the auto-configured builder carries
        // ObservationRestClientCustomizer, so a static one emits no client metrics or spans.
        this.alpaca = builder
                .baseUrl(endpoint)
                .defaultHeader("APCA-API-KEY-ID", apiKey)
                .defaultHeader("APCA-API-SECRET-KEY", apiSecret)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
        this.targetSchema = identifier(targetSchema);
        this.defaultReferencePrice = defaultReferencePrice;
    }

    AlpacaReferenceDataImporter(JdbcTemplate jdbc, RestClient alpaca, String targetSchema, BigDecimal defaultReferencePrice) {
        this.jdbc = jdbc;
        this.alpaca = alpaca;
        this.targetSchema = identifier(targetSchema);
        this.defaultReferencePrice = defaultReferencePrice;
    }

    @Override
    public void run(ApplicationArguments args) {
        Asset[] assets = alpaca.get()
                .uri("/assets?status=active&asset_class=us_equity")
                .retrieve()
                .body(Asset[].class);
        if (assets == null) assets = new Asset[0];

        int imported = 0;
        for (Asset asset : assets) {
            if (!asset.importable()) continue;
            Exchange exchange = exchange(asset.exchange());
            upsert(asset, exchange.mic(), exchange.name(), stableId(asset.id(), exchange.mic()),
                    asset.fractionable() ? new BigDecimal("0.000001") : BigDecimal.ONE);
            upsert(asset, "XOSR", "Smart Order Router", stableId(asset.id(), "XOSR"),
                    asset.fractionable() ? new BigDecimal("0.000001") : BigDecimal.ONE);
            imported += 2;
        }
        log.info("Imported or updated {} Alpaca primary/composite listings from {} active assets",
                imported, assets.length);
    }

    private void upsert(Asset asset, String mic, String exchangeName, long id, BigDecimal sizeIncrement) {
        String sql = """
                INSERT INTO %s.instrument_listing
                    (id, version, symbol, name, market_symbol, exchange_mic, exchange_name,
                     country_code, currency, enabled, tick_size, size_increment,
                     reference_price, previous_close)
                VALUES (?, 1, ?, ?, ?, ?, ?, 'US', 'USD', TRUE, 0.01, ?, ?, ?)
                ON CONFLICT (market_symbol, exchange_mic) DO UPDATE SET
                    version = %s.instrument_listing.version + 1,
                    symbol = EXCLUDED.symbol,
                    name = EXCLUDED.name,
                    exchange_name = EXCLUDED.exchange_name,
                    enabled = EXCLUDED.enabled,
                    size_increment = EXCLUDED.size_increment
                """.formatted(targetSchema, targetSchema);
        jdbc.update(sql, id, limited(asset.symbol(), 24), limited(asset.name(), 200),
                limited(asset.symbol(), 24), mic, exchangeName, sizeIncrement,
                defaultReferencePrice, defaultReferencePrice);
    }

    static Exchange exchange(String value) {
        return switch (value == null ? "" : value.toUpperCase(Locale.ROOT)) {
            case "NASDAQ" -> new Exchange("XNAS", "Nasdaq");
            case "NYSE" -> new Exchange("XNYS", "New York Stock Exchange");
            case "ARCA", "NYSEARCA" -> new Exchange("ARCX", "NYSE Arca");
            case "AMEX" -> new Exchange("XASE", "NYSE American");
            case "BATS" -> new Exchange("BATS", "Cboe BZX");
            default -> new Exchange("XNAS", limited(
                    value == null || value.isBlank() ? "US Equities" : value, 120));
        };
    }

    static long stableId(String sourceId, String mic) {
        UUID uuid = UUID.nameUUIDFromBytes((sourceId + ":" + mic).getBytes(StandardCharsets.UTF_8));
        long value = uuid.getMostSignificantBits() & Long.MAX_VALUE;
        return value < 2_000 ? value + 2_000 : value;
    }

    private static String limited(String value, int length) {
        String normalized = value == null || value.isBlank() ? "Unknown" : value.strip();
        return normalized.substring(0, Math.min(normalized.length(), length));
    }

    private static String identifier(String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Database schema must be a simple SQL identifier");
        }
        return value;
    }

    record Asset(String id, String symbol, String name, String exchange, String status,
                 boolean tradable, boolean fractionable) {
        boolean importable() {
            return id != null && !id.isBlank() && symbol != null && !symbol.isBlank()
                    && "active".equalsIgnoreCase(status) && tradable;
        }
    }

    record Exchange(String mic, String name) {
    }
}
