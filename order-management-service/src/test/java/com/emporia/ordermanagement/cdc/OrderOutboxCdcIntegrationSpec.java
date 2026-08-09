package com.emporia.ordermanagement.cdc;

import com.emporia.events.TradingEvents.OrderDomainEvent;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.sbe.SbeEncoderDecoder;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the checked-in Debezium connector config, not a stand-in copy of
 * it, actually relays a row written the way {@code AsyncDbWriter} writes it
 * - byte-for-byte - onto the real Kafka topic. This is the regression cover
 * {@code OutboxDispatcherTest} provided for the polling design and nothing
 * replaced when the outbox moved to CDC.
 *
 * <p>Opt-in integration specification selected by the {@code cdc-it} Maven
 * profile: it starts Postgres, Kafka, and a real Kafka Connect (Debezium)
 * container, which is too slow for the default {@code mvn test} loop.
 */
@Testcontainers
class OrderOutboxCdcIntegrationSpec {

    private static final String CONNECTOR_NAME = "order-outbox-connector";
    // The checked-in connector config hardcodes this as database.hostname,
    // so the Postgres container answers to the same name on the shared
    // network - the point is to exercise the real file unmodified.
    private static final String POSTGRES_ALIAS = "order-management-postgres";
    private static final String KAFKA_ALIAS = "kafka";

    // Not annotated @Container: Network doesn't implement Startable. The
    // containers below hold it alive via .withNetwork(...); it's reclaimed
    // by Testcontainers' Ryuk cleanup, same as everything else here.
    static final Network network = Network.newNetwork();

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withNetwork(network)
            .withNetworkAliases(POSTGRES_ALIAS)
            .withDatabaseName("emporia_order_management")
            .withUsername("postgres")
            .withPassword("admin123")
            .withCommand("postgres", "-c", "wal_level=logical", "-c", "max_replication_slots=4", "-c", "max_wal_senders=4");

    // KafkaContainer.getBootstrapServers() only ever advertises the
    // host-mapped address (for this JVM's own consumer below); its default
    // internal listener advertises the container's raw Docker hostname, not
    // this network's "kafka" alias. withListener(...) is what actually binds
    // an extra listener to that alias so another container - kafkaConnect -
    // can resolve and reconnect to it using the *same* address it dialed in
    // on, instead of timing out on an unreachable advertised address.
    private static final int KAFKA_INTERNAL_PORT = 19092;

    @Container
    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"))
            .withNetwork(network)
            .withListener(KAFKA_ALIAS + ":" + KAFKA_INTERNAL_PORT);

    @Container
    static final GenericContainer<?> kafkaConnect = new GenericContainer<>(DockerImageName.parse("debezium/connect:3.0.0.Final"))
            .withNetwork(network)
            .withExposedPorts(8083)
            .withEnv("BOOTSTRAP_SERVERS", KAFKA_ALIAS + ":" + KAFKA_INTERNAL_PORT)
            .withEnv("GROUP_ID", "cdc-it-outbox-connect")
            .withEnv("CONFIG_STORAGE_TOPIC", "connect-configs")
            .withEnv("OFFSET_STORAGE_TOPIC", "connect-offsets")
            .withEnv("STATUS_STORAGE_TOPIC", "connect-status")
            .withEnv("CONFIG_STORAGE_REPLICATION_FACTOR", "1")
            .withEnv("OFFSET_STORAGE_REPLICATION_FACTOR", "1")
            .withEnv("STATUS_STORAGE_REPLICATION_FACTOR", "1")
            .withEnv("CONNECT_EXACTLY_ONCE_SOURCE_SUPPORT", "enabled")
            .waitingFor(Wait.forHttp("/connectors").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)))
            .dependsOn(kafka, postgres);

    @BeforeAll
    static void migrateSchemaAndRegisterConnector() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .defaultSchema("emporia_order_data")
                .schemas("emporia_order_data")
                .createSchemas(true)
                .load()
                .migrate();

        String connectorConfig = Files.readString(
                Path.of("..", "deploy", "debezium", "order-outbox-connector.json"));

        HttpClient client = HttpClient.newHttpClient();
        String connectUrl = "http://" + kafkaConnect.getHost() + ":" + kafkaConnect.getMappedPort(8083);
        HttpResponse<String> registration = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(connectUrl + "/connectors/" + CONNECTOR_NAME + "/config"))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(connectorConfig))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(registration.statusCode())
                .as("connector registration: %s", registration.body())
                .isBetween(200, 299);

        awaitConnectorTaskRunning(client, connectUrl);
    }

    private static void awaitConnectorTaskRunning(HttpClient client, String connectUrl) throws Exception {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(60).toMillis();
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> status = client.send(
                    HttpRequest.newBuilder(URI.create(connectUrl + "/connectors/" + CONNECTOR_NAME + "/status")).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (status.statusCode() == 200 && status.body().contains("\"state\":\"RUNNING\"")
                    && status.body().contains("\"tasks\"") && !status.body().contains("\"state\":\"FAILED\"")) {
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("order-outbox-connector task did not reach RUNNING in time");
    }

    @Test
    void rowWrittenLikeAsyncDbWriterRelaysByteForByteToKafka() throws Exception {
        UUID orderId = UUID.randomUUID();
        String routingKey = "cdc-it-" + UUID.randomUUID();
        OrderDomainEvent event = new OrderDomainEvent(
                com.emporia.events.TradingEvents.SCHEMA_VERSION,
                UUID.randomUUID(),
                UUID.randomUUID(),
                orderId,
                "cdc-it-trader",
                "default",
                "CREATED",
                1L,
                OrderStatus.LIVE,
                Instant.parse("2026-01-01T00:00:00Z"),
                "{}"
        );
        byte[] payload = SbeEncoderDecoder.encodeOrderDomainEvent(event);
        assertThat(SbeEncoderDecoder.isSbePayload(payload)).isTrue();

        insertOutboxRow("emporia.orders.v1", routingKey, "ORDER_EVENT", payload);

        byte[] relayed = consumeOne("emporia.orders.v1", routingKey, Duration.ofSeconds(30));

        assertThat(relayed).isEqualTo(payload);
        assertThat(SbeEncoderDecoder.isSbePayload(relayed)).isTrue();
        OrderDomainEvent decoded = SbeEncoderDecoder.decodeOrderDomainEvent(relayed);
        assertThat(decoded.orderId()).isEqualTo(orderId);
        assertThat(decoded.eventType()).isEqualTo("CREATED");
    }

    private void insertOutboxRow(String topic, String routingKey, String payloadType, byte[] payload) throws Exception {
        String sql = """
                INSERT INTO emporia_order_data.order_outbox (topic, routing_key, payload_type, payload, created_at)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, topic);
            statement.setString(2, routingKey);
            statement.setString(3, payloadType);
            statement.setBytes(4, payload);
            statement.setTimestamp(5, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }

    private byte[] consumeOne(String topic, String expectedKey, Duration timeout) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "cdc-it-consumer-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");

        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, byte[]> record : records) {
                    if (expectedKey.equals(record.key())) {
                        return record.value();
                    }
                }
            }
        }
        throw new AssertionError("Did not see key " + expectedKey + " on topic " + topic + " within " + timeout);
    }
}
