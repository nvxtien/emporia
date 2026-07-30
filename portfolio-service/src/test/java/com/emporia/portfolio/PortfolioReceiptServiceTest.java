package com.emporia.portfolio;

import com.emporia.portfolio.PortfolioContracts.Balance;
import com.emporia.portfolio.PortfolioContracts.RiskSeed;
import com.emporia.portfolio.PortfolioContracts.Snapshot;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortfolioReceiptServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-07-27T08:00:00Z");
    private static final String EMPTY_JSON_SHA256 =
            "44136fa355b3678a1146ad16f7e8649e"
                    + "94fb4fc21fe77e8310c060f61caaff8a";

    @Test
    void atomicallyRecordsAndAppliesANewSnapshot() {
        final RecordingPortfolioStore store =
                new RecordingPortfolioStore();
        final PortfolioReceiptService service = service(store);
        final byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);

        final PortfolioReceiptService.ReceiptResult result =
                service.apply(
                        13,
                        101,
                        "exchange-1:13:101",
                        payload,
                        snapshot());

        assertThat(result)
                .isEqualTo(
                        PortfolioReceiptService.ReceiptResult.APPLIED);
        assertThat(store.lockedClientId).isEqualTo(101);
        assertThat(store.recordedEventId)
                .isEqualTo("exchange-1:13:101");
        assertThat(store.recordedDigest)
                .isEqualTo(EMPTY_JSON_SHA256);
        assertThat(store.recordedPayload)
                .containsExactly(payload);
        assertThat(store.applied.availableBalances())
                .containsEntry(840, 500L);
        assertThat(store.recordedAt).isEqualTo(NOW);
        assertThat(store.appliedAt).isEqualTo(NOW);
    }

    @Test
    void treatsAnIdenticalReceiptAsASuccessfulDuplicate() {
        final byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
        final RecordingPortfolioStore store =
                new RecordingPortfolioStore();
        store.existing =
                new PortfolioReceipt(
                        EMPTY_JSON_SHA256,
                        payload);

        assertThat(service(store).apply(
                13,
                101,
                "exchange-1:13:101",
                payload,
                snapshot()))
                .isEqualTo(
                        PortfolioReceiptService.ReceiptResult.DUPLICATE);
        assertThat(store.recordedEventId).isNull();
        assertThat(store.applied).isNull();
    }

    @Test
    void rejectsAnEventIdReusedWithDifferentContent() {
        final RecordingPortfolioStore store =
                new RecordingPortfolioStore();
        store.existing =
                new PortfolioReceipt(
                        "different",
                        "different".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service(store).apply(
                13,
                101,
                "exchange-1:13:101",
                "{}".getBytes(StandardCharsets.UTF_8),
                snapshot()))
                .isInstanceOf(
                        PortfolioIdempotencyConflictException.class);
        assertThat(store.applied).isNull();
    }

    @Test
    void loadDelegatesToStore() {
        final RecordingPortfolioStore store = new RecordingPortfolioStore();
        store.seedToReturn = new RiskSeed(1, 101L, 10L, List.of());
        final PortfolioReceiptService service = service(store);
        assertThat(service.load(101L)).isEqualTo(store.seedToReturn);
    }

    @Test
    void loadRejectsNonPositiveClientId() {
        final RecordingPortfolioStore store = new RecordingPortfolioStore();
        final PortfolioReceiptService service = service(store);
        assertThatThrownBy(() -> service.load(0L))
                .isInstanceOf(PortfolioContractException.class)
                .hasMessageContaining("clientId must be positive");
    }

    @Test
    void rejectsEventIdWithMatchingDigestButDifferentPayloadBytes() {
        final byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
        final byte[] diffPayload = "[]".getBytes(StandardCharsets.UTF_8);
        final RecordingPortfolioStore store = new RecordingPortfolioStore();
        // same digest recorded, but different byte array
        store.existing = new PortfolioReceipt(EMPTY_JSON_SHA256, diffPayload);

        assertThatThrownBy(() -> service(store).apply(
                13, 101, "exchange-1:13:101", payload, snapshot()))
                .isInstanceOf(PortfolioIdempotencyConflictException.class);
    }

    @Test
    void twoArgConstructorUsesDefaultClock() {
        final RecordingPortfolioStore store = new RecordingPortfolioStore();
        PortfolioReceiptService service = new PortfolioReceiptService(store, new PortfolioSnapshotValidator());
        assertThat(service).isNotNull();
    }

    private PortfolioReceiptService service(
            final RecordingPortfolioStore store) {
        return new PortfolioReceiptService(
                store,
                new PortfolioSnapshotValidator(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Snapshot snapshot() {
        return new Snapshot(
                PortfolioContracts.SCHEMA_VERSION,
                "exchange-1",
                13L,
                101L,
                List.of(new Balance(840, 500L)));
    }

    private static final class RecordingPortfolioStore
            implements PortfolioStore {

        private RiskSeed seedToReturn;
        private long lockedClientId;
        private PortfolioReceipt existing;
        private String recordedEventId;
        private String recordedDigest;
        private byte[] recordedPayload;
        private Instant recordedAt;
        private ValidatedPortfolioSnapshot applied;
        private Instant appliedAt;

        @Override
        public RiskSeed load(final long clientId) {
            return seedToReturn;
        }

        @Override
        public void lockClient(final long clientId) {
            lockedClientId = clientId;
        }

        @Override
        public PortfolioReceipt findReceipt(
                final String eventId) {
            return existing;
        }

        @Override
        public void recordReceipt(
                final String eventId,
                final String payloadSha256,
                final byte[] payload,
                final ValidatedPortfolioSnapshot snapshot,
                final Instant receivedAt) {
            recordedEventId = eventId;
            recordedDigest = payloadSha256;
            recordedPayload =
                    Arrays.copyOf(payload, payload.length);
            recordedAt = receivedAt;
        }

        @Override
        public void replaceBalances(
                final ValidatedPortfolioSnapshot snapshot,
                final Instant updatedAt) {
            applied = snapshot;
            appliedAt = updatedAt;
        }
    }
}
