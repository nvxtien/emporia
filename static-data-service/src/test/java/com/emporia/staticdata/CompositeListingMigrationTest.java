package com.emporia.staticdata;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeListingMigrationTest {

    @Test
    void addsOneXosrCompositeForEverySeededVenueListing() throws Exception {
        String database = "static_data_" + UUID.randomUUID().toString().replace("-", "");
        String url = "jdbc:h2:mem:" + database
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway flyway = Flyway.configure()
                .dataSource(url, "sa", "")
                .schemas("emporia_static_data")
                .defaultSchema("emporia_static_data")
                .locations("classpath:db/migration")
                .load();

        flyway.migrate();

        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("""
                    SELECT COUNT(*)
                    FROM emporia_static_data.instrument_listing
                    WHERE exchange_mic = 'XOSR'
                    """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(12);
            }

            try (var rows = statement.executeQuery("""
                    SELECT symbol, market_symbol, exchange_name
                    FROM emporia_static_data.instrument_listing
                    WHERE id = 1001
                    """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("symbol")).isEqualTo("AAPL");
                assertThat(rows.getString("market_symbol")).isEqualTo("AAPL");
                assertThat(rows.getString("exchange_name")).isEqualTo("Smart Order Router");
            }
        }

        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
}
