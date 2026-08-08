package com.emporia.ordermanagement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderDatabaseConstraintTest {
    private static final String SCHEMA = "emporia_order_data";

    @Test
    void flywayAppliesInvariantConstraintsAndRejectsInvalidDirectWrites() throws Exception {
        String databaseName = "order_constraints_" + UUID.randomUUID().toString().replace("-", "");
        String url = "jdbc:h2:mem:" + databaseName
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .defaultSchema(SCHEMA)
                    .schemas(SCHEMA)
                    .createSchemas(true)
                    .load();

            flyway.migrate();

            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("8");
            UUID orderId = insertValidOrder(connection);

            assertRejected(connection, orderId, """
                    UPDATE emporia_order_data.trading_order
                    SET quantity = 100.005, remaining_quantity = 100.005
                    WHERE id = ?
                    """);
            assertRejected(connection, orderId, """
                    UPDATE emporia_order_data.trading_order
                    SET limit_price = 25.005
                    WHERE id = ?
                    """);
            assertRejected(connection, orderId, """
                    UPDATE emporia_order_data.trading_order
                    SET traded_quantity = 101.00, remaining_quantity = -1.00
                    WHERE id = ?
                    """);
            assertRejected(connection, orderId, """
                    UPDATE emporia_order_data.trading_order
                    SET traded_quantity = 10.00, remaining_quantity = 90.00
                    WHERE id = ?
                    """);

            assertThat(update(connection, orderId, """
                    UPDATE emporia_order_data.trading_order
                    SET traded_quantity = 25.00,
                        remaining_quantity = 75.00,
                        order_status = 'PARTIALLY_FILLED'
                    WHERE id = ?
                    """)).isEqualTo(1);
            assertThat(update(connection, orderId, """
                    UPDATE emporia_order_data.trading_order
                    SET order_status = 'CANCELLED',
                        target_status = 'CANCELLED'
                    WHERE id = ?
                    """)).isEqualTo(1);
        }
    }

    private static UUID insertValidOrder(Connection connection) throws SQLException {
        UUID orderId = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO emporia_order_data.trading_order (
                    id, entity_version, user_subject, desk_id,
                    listing_id, listing_version, listing_symbol, listing_name,
                    market_symbol, exchange_mic, exchange_name, country_code, currency,
                    tick_size, size_increment, reference_price, previous_close,
                    order_side, order_type, quantity, limit_price,
                    remaining_quantity, traded_quantity, average_trade_price,
                    order_status, target_status, destination, originator_reference,
                    parent_order_id, root_order_id, execution_parameters, error_message,
                    created_at, updated_at
                ) VALUES (
                    ?, 0, 'database-test-user', 'database-test-desk',
                    1, 1, 'AAPL', 'Apple Inc.',
                    'AAPL', 'XNAS', 'Nasdaq', 'US', 'USD',
                    0.01, 0.01, 200.00, 198.00,
                    'BUY', 'LIMIT', 100.00, 25.00,
                    100.00, 0.00, NULL,
                    'LIVE', 'LIVE', 'DMA', 'database-test',
                    NULL, ?, '{}', NULL,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """)) {
            statement.setObject(1, orderId);
            statement.setObject(2, orderId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
        return orderId;
    }

    private static void assertRejected(Connection connection, UUID orderId, String sql) {
        assertThatThrownBy(() -> update(connection, orderId, sql))
                .isInstanceOf(SQLException.class);
    }

    private static int update(Connection connection, UUID orderId, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, orderId);
            return statement.executeUpdate();
        }
    }
}
