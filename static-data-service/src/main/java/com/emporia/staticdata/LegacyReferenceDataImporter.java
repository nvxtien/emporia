package com.emporia.staticdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Pattern;

@Component
class LegacyReferenceDataImporter implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LegacyReferenceDataImporter.class);
    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final String targetSchema;
    private final String legacyReferenceSchema;

    LegacyReferenceDataImporter(
            JdbcTemplate jdbc,
            @Value("${emporia.static-data.legacy-import-enabled:true}") boolean enabled,
            @Value("${spring.flyway.default-schema:emporia_static_data}") String targetSchema,
            @Value("${emporia.static-data.legacy-reference-schema:referencedata}") String legacyReferenceSchema) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.targetSchema = identifier(targetSchema);
        this.legacyReferenceSchema = identifier(legacyReferenceSchema);
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled || !legacyTablesExist()) {
            log.info("Legacy reference-data import skipped; normalized OpenTP tables were not found");
            return;
        }

        String sql = """
                INSERT INTO %s.instrument_listing
                    (id, version, symbol, name, market_symbol, exchange_mic, exchange_name, country_code,
                     currency, enabled, tick_size, size_increment, reference_price, previous_close)
                SELECT
                    l.id,
                    1,
                    LEFT(i.display_symbol, 24),
                    LEFT(i.name, 200),
                    LEFT(l.market_symbol, 24),
                    LEFT(m.mic, 12),
                    LEFT(m.name, 120),
                    LEFT(m.country_code, 2),
                    CASE m.country_code
                        WHEN 'GB' THEN 'GBP'
                        WHEN 'JP' THEN 'JPY'
                        WHEN 'CA' THEN 'CAD'
                        WHEN 'AU' THEN 'AUD'
                        WHEN 'CH' THEN 'CHF'
                        WHEN 'DE' THEN 'EUR'
                        WHEN 'FR' THEN 'EUR'
                        WHEN 'NL' THEN 'EUR'
                        ELSE 'USD'
                    END,
                    i.enabled,
                    0.01,
                    1,
                    100,
                    100
                FROM %s.listings l
                JOIN %s.instruments i ON i.id = l.instrument_id
                JOIN %s.markets m ON m.id = l.market_id
                ON CONFLICT (market_symbol, exchange_mic) DO UPDATE SET
                    version = EXCLUDED.version,
                    symbol = EXCLUDED.symbol,
                    name = EXCLUDED.name,
                    exchange_name = EXCLUDED.exchange_name,
                    country_code = EXCLUDED.country_code,
                    currency = EXCLUDED.currency,
                    enabled = EXCLUDED.enabled
                """.formatted(targetSchema, legacyReferenceSchema, legacyReferenceSchema, legacyReferenceSchema);

        int imported = jdbc.update(sql);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + targetSchema + ".instrument_listing", Long.class);
        log.info("Imported or updated {} legacy listings; Emporia static data now contains {} listings", imported, total);
    }

    private boolean legacyTablesExist() {
        try {
            Boolean present = jdbc.queryForObject("""
                    SELECT to_regclass(?) IS NOT NULL
                       AND to_regclass(?) IS NOT NULL
                       AND to_regclass(?) IS NOT NULL
                    """, Boolean.class,
                    legacyReferenceSchema + ".instruments",
                    legacyReferenceSchema + ".listings",
                    legacyReferenceSchema + ".markets");
            return Boolean.TRUE.equals(present);
        } catch (RuntimeException unsupportedDatabase) {
            log.debug("Database does not support PostgreSQL legacy-table discovery", unsupportedDatabase);
            return false;
        }
    }

    private static String identifier(String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Database schema must be a simple SQL identifier");
        }
        return value;
    }
}
