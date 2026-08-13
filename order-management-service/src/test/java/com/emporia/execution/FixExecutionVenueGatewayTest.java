package com.emporia.execution;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.TradingEvents.OrderView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixExecutionVenueGatewayTest {
    private ServerSocket serverSocket;
    private int port;
    private Thread serverThread;
    private final RecordingCommands commands = new RecordingCommands();
    private final InMemoryFixSessionStateStore sessionState = new InMemoryFixSessionStateStore();
    private final InMemoryFixMessageLogStore messageLog = new InMemoryFixMessageLogStore();
    private FixExecutionVenueGateway gateway;
    private final Queue<String> clientMessages = new ArrayDeque<>();
    private volatile OutputStream serverOut;
    // Every message the fake server sends (Logon reply, Logout reply, and any
    // exec reports a test crafts by hand) shares this counter, so the gateway's
    // gap-detection never sees a spurious gap in what the server sends it.
    private final AtomicInteger serverSeq = new AtomicInteger();

    private int nextServerSeq() {
        return serverSeq.incrementAndGet();
    }

    @BeforeEach
    void setUp() throws Exception {
        serverSocket = new ServerSocket(0);
        port = serverSocket.getLocalPort();
        serverThread = new Thread(() -> {
            try {
                Socket socket = serverSocket.accept();
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                serverOut = out;
                byte[] buf = new byte[4096];
                while (!serverSocket.isClosed()) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    String msg = new String(buf, 0, n, StandardCharsets.UTF_8);
                    synchronized (clientMessages) {
                        clientMessages.add(msg);
                    }
                    if (msg.contains("35=A")) { // Logon response
                        String reply = "8=FIX.4.2\0019=60\00135=A\00149=EXCHANGE\00156=CLIENT\00134=" + nextServerSeq()
                                + "\00152=20260727-00:00:00.000\00198=0\001108=30\00110=000\001";
                        out.write(reply.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } else if (msg.contains("35=5")) { // Logout acknowledgement, so tearDown's handshake completes fast
                        String reply = "8=FIX.4.2\0019=59\00135=5\00149=EXCHANGE\00156=CLIENT\00134=" + nextServerSeq()
                                + "\00152=20260727-00:00:00.000\00110=000\001";
                        out.write(reply.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                }
            } catch (Exception ignored) {
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        gateway = new FixExecutionVenueGateway("XNAS=127.0.0.1:" + port + ":CLIENT:EXCHANGE",
                commands, sessionState, messageLog);
        gateway.start();
        // Wait for logon
        TimeUnit.MILLISECONDS.sleep(200);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (gateway != null) gateway.stop();
        if (serverSocket != null) serverSocket.close();
    }

    @Test
    void rejectsInvalidFixVenuesDefinition() {
        assertThatThrownBy(() -> new FixExecutionVenueGateway("XNAS=invalid", commands, sessionState, messageLog))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MIC=host:port:senderCompId:targetCompId");
    }

    @Test
    void submitThrowsWhenSessionNotFound() {
        FixExecutionVenueGateway emptyGateway = new FixExecutionVenueGateway("", commands, sessionState, messageLog);
        OrderView order = sampleOrder(UUID.randomUUID(), new BigDecimal("150.00"));

        assertThatThrownBy(() -> emptyGateway.submit(order))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No FIX execution session is configured for XNAS");
    }

    @Test
    void submitsLimitOrderOverFixSession() throws Exception {
        OrderView order = sampleOrder(UUID.randomUUID(), new BigDecimal("150.00"));
        gateway.submit(order);
        TimeUnit.MILLISECONDS.sleep(100);

        synchronized (clientMessages) {
            System.out.println("DEBUG clientMessages: " + clientMessages);
            assertThat(clientMessages.stream().anyMatch(m -> m.contains("35=D"))).isTrue();
        }
    }

    @Test
    void modifiesLimitOrderOverFixSession() throws Exception {
        OrderView order = sampleOrder(UUID.randomUUID(), new BigDecimal("155.00"));
        gateway.submit(order);
        gateway.modify(order);
        TimeUnit.MILLISECONDS.sleep(100);

        synchronized (clientMessages) {
            assertThat(clientMessages.stream().anyMatch(m -> m.contains("35=G"))).isTrue();
        }
    }

    @Test
    void processesIncomingExecutionReportFillsAndRejects() throws Exception {
        OrderView order = sampleOrder(UUID.randomUUID(), new BigDecimal("150.00"));
        gateway.submit(order);

        // Send execution report fill from server
        String fillReport = "8=FIX.4.2\0019=120\00135=8\00149=EXCHANGE\00156=CLIENT\00134=" + nextServerSeq()
                + "\00152=20260727-00:00:00.000\00111="
                + order.id() + "\00135=8\001150=2\00139=2\00132=10\00131=150.00\00117=exec-99\00110=000\001";
        serverOut.write(fillReport.getBytes(StandardCharsets.UTF_8));
        serverOut.flush();

        TimeUnit.MILLISECONDS.sleep(100);
        assertThat(commands.fills).hasSize(1);

        // Send execution report cancel
        String cancelReport = "8=FIX.4.2\0019=120\00135=8\00149=EXCHANGE\00156=CLIENT\00134=" + nextServerSeq()
                + "\00152=20260727-00:00:00.000\00111="
                + order.id() + "\00135=8\001150=4\00139=4\00117=exec-100\00110=000\001";
        serverOut.write(cancelReport.getBytes(StandardCharsets.UTF_8));
        serverOut.flush();

        TimeUnit.MILLISECONDS.sleep(100);
        assertThat(commands.cancellations).hasSize(1);
    }

    @Test
    void cancelsLimitOrderOverFixSession() throws Exception {
        OrderView order = sampleOrder(UUID.randomUUID(), new BigDecimal("150.00"));
        gateway.submit(order);
        gateway.cancel(order);
        TimeUnit.MILLISECONDS.sleep(100);

        synchronized (clientMessages) {
            assertThat(clientMessages.stream().anyMatch(m -> m.contains("35=F"))).isTrue();
        }
    }

    @Test
    void processesIncomingExecutionReportRejects() throws Exception {
        OrderView order = sampleOrder(UUID.randomUUID(), new BigDecimal("150.00"));
        gateway.submit(order);

        // Send execution report reject (150=8)
        String rejectReport = "8=FIX.4.2\0019=120\00135=8\00149=EXCHANGE\00156=CLIENT\00134=" + nextServerSeq()
                + "\00152=20260727-00:00:00.000\00111="
                + order.id() + "\00135=8\001150=8\00139=8\00158=Price out of bounds\00117=exec-101\00110=000\001";
        serverOut.write(rejectReport.getBytes(StandardCharsets.UTF_8));
        serverOut.flush();

        TimeUnit.MILLISECONDS.sleep(100);
        assertThat(commands.rejections).hasSize(1);
    }

    @Test
    void modifyAndCancelThrowWhenSessionNotFound() {
        FixExecutionVenueGateway emptyGateway = new FixExecutionVenueGateway("", commands, sessionState, messageLog);
        OrderView order = sampleOrder(UUID.randomUUID(), new BigDecimal("150.00"));

        assertThatThrownBy(() -> emptyGateway.modify(order))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No FIX execution session is configured for XNAS");

        assertThatThrownBy(() -> emptyGateway.cancel(order))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No FIX execution session is configured for XNAS");
    }

    @Test
    void recoversOrder() {
        OrderView order = sampleOrder(UUID.randomUUID(), new BigDecimal("150.00"));
        gateway.recover(order);
    }

    @Test
    void logonOnAFreshSessionRequestsSequenceReset() {
        synchronized (clientMessages) {
            assertThat(clientMessages.stream().anyMatch(m -> m.contains("35=A") && m.contains("141=Y"))).isTrue();
        }
    }

    @Test
    void stopSendsALogoutAndWaitsForTheAcknowledgement() {
        long startedAt = System.nanoTime();
        gateway.stop();
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        synchronized (clientMessages) {
            assertThat(clientMessages.stream().anyMatch(m -> m.contains("35=5"))).isTrue();
        }
        // The fake server acknowledges Logout immediately; a well-behaved handshake
        // should not burn the full 2-second timeout waiting for it.
        assertThat(elapsedMillis).isLessThan(1_000);
    }

    @Test
    void sessionLevelRejectDoesNotDisruptExecutionReportProcessing() throws Exception {
        OrderView order = sampleOrder(UUID.randomUUID(), new BigDecimal("150.00"));
        gateway.submit(order);

        String reject = "8=FIX.4.2\0019=90\00135=3\00149=EXCHANGE\00156=CLIENT\00134=" + nextServerSeq()
                + "\00152=20260727-00:00:00.000\001"
                + "45=2\001371=44\001372=D\001373=5\00158=Invalid tag\00110=000\001";
        serverOut.write(reject.getBytes(StandardCharsets.UTF_8));
        serverOut.flush();
        TimeUnit.MILLISECONDS.sleep(100);

        assertThat(commands.fills).isEmpty();
        assertThat(commands.rejections).isEmpty();

        // The session keeps working after a session-level Reject.
        String fillReport = "8=FIX.4.2\0019=120\00135=8\00149=EXCHANGE\00156=CLIENT\00134=" + nextServerSeq()
                + "\00152=20260727-00:00:00.000\00111="
                + order.id() + "\00135=8\001150=2\00139=2\00132=10\00131=150.00\00117=exec-102\00110=000\001";
        serverOut.write(fillReport.getBytes(StandardCharsets.UTF_8));
        serverOut.flush();
        TimeUnit.MILLISECONDS.sleep(100);

        assertThat(commands.fills).hasSize(1);
    }

    @Test
    void sendingMessagesPersistsTheNextOutgoingSequenceNumber() throws Exception {
        // Logon (setUp) already consumed sequence 1, so the persisted "next" value is 2.
        assertThat(sessionState.loadForToday("XNAS").outgoingSeqNum()).isEqualTo(2);

        OrderView order = sampleOrder(UUID.randomUUID(), new BigDecimal("150.00"));
        gateway.submit(order);
        TimeUnit.MILLISECONDS.sleep(100);

        assertThat(sessionState.loadForToday("XNAS").outgoingSeqNum()).isEqualTo(3);
    }

    @Test
    void incomingExecutionReportsPersistTheNextIncomingSequenceNumber() throws Exception {
        OrderView order = sampleOrder(UUID.randomUUID(), new BigDecimal("150.00"));
        gateway.submit(order);

        int seq = nextServerSeq();
        String fillReport = "8=FIX.4.2\0019=120\00135=8\00149=EXCHANGE\00156=CLIENT\00134=" + seq
                + "\00152=20260727-00:00:00.000\00111="
                + order.id() + "\00135=8\001150=2\00139=2\00132=10\00131=150.00\00117=exec-99\00110=000\001";
        serverOut.write(fillReport.getBytes(StandardCharsets.UTF_8));
        serverOut.flush();
        TimeUnit.MILLISECONDS.sleep(100);

        assertThat(sessionState.loadForToday("XNAS").incomingSeqNum()).isEqualTo(seq + 1);
    }

    @Test
    void outOfOrderMessagesAreBufferedAndReleasedOnceTheGapIsFilled() throws Exception {
        OrderView order = sampleOrder(UUID.randomUUID(), new BigDecimal("150.00"));
        gateway.submit(order);

        int missedSeq = nextServerSeq();
        int arrivingSeq = nextServerSeq();
        String secondFill = "8=FIX.4.2\0019=120\00135=8\00149=EXCHANGE\00156=CLIENT\00134=" + arrivingSeq
                + "\00152=20260727-00:00:00.000\00111=" + order.id()
                + "\00135=8\001150=2\00139=2\00132=5\00131=151.00\00117=exec-2\00110=000\001";
        serverOut.write(secondFill.getBytes(StandardCharsets.UTF_8));
        serverOut.flush();
        TimeUnit.MILLISECONDS.sleep(150);

        // Not processed yet - it arrived ahead of the message we're still expecting.
        assertThat(commands.fills).isEmpty();
        synchronized (clientMessages) {
            assertThat(clientMessages.stream().anyMatch(m ->
                    m.contains("35=2") && m.contains("7=" + missedSeq) && m.contains("16=" + missedSeq))).isTrue();
        }

        // The venue "resends" the missing message using its original sequence number.
        String firstFill = "8=FIX.4.2\0019=120\00135=8\00143=Y\00149=EXCHANGE\00156=CLIENT\00134=" + missedSeq
                + "\00152=20260727-00:00:00.000\00111=" + order.id()
                + "\00135=8\001150=2\00139=2\00132=10\00131=150.00\00117=exec-1\00110=000\001";
        serverOut.write(firstFill.getBytes(StandardCharsets.UTF_8));
        serverOut.flush();
        TimeUnit.MILLISECONDS.sleep(150);

        // Both the resent message and the one that had been buffered are now applied.
        assertThat(commands.fills).hasSize(2);
    }

    @Test
    void respondsToResendRequestByReplayingLoggedApplicationMessages() throws Exception {
        OrderView order = sampleOrder(UUID.randomUUID(), new BigDecimal("150.00"));
        gateway.submit(order);
        TimeUnit.MILLISECONDS.sleep(100);
        synchronized (clientMessages) {
            clientMessages.clear();
        }

        // Ask us to resend from the NewOrderSingle (seq 2) through current (16=0).
        String resendRequest = "8=FIX.4.2\0019=70\00135=2\00149=EXCHANGE\00156=CLIENT\00134=" + nextServerSeq()
                + "\00152=20260727-00:00:00.000\0017=2\00116=0\00110=000\001";
        serverOut.write(resendRequest.getBytes(StandardCharsets.UTF_8));
        serverOut.flush();
        TimeUnit.MILLISECONDS.sleep(150);

        synchronized (clientMessages) {
            assertThat(clientMessages.stream().anyMatch(m ->
                    m.contains("35=D") && m.contains("34=2") && m.contains("43=Y"))).isTrue();
        }
    }

    @Test
    void respondsToResendRequestWithGapFillForUnloggedAdminMessages() throws Exception {
        // Trigger an outgoing Heartbeat (our seq 2) - admin, never logged - by
        // sending a TestRequest.
        String testRequest = "8=FIX.4.2\0019=70\00135=1\00149=EXCHANGE\00156=CLIENT\00134=" + nextServerSeq()
                + "\00152=20260727-00:00:00.000\001112=probe\00110=000\001";
        serverOut.write(testRequest.getBytes(StandardCharsets.UTF_8));
        serverOut.flush();
        TimeUnit.MILLISECONDS.sleep(150);
        synchronized (clientMessages) {
            clientMessages.clear();
        }

        // Ask us to resend just our seq 2 (the Heartbeat) - nothing was logged there.
        String resendRequest = "8=FIX.4.2\0019=70\00135=2\00149=EXCHANGE\00156=CLIENT\00134=" + nextServerSeq()
                + "\00152=20260727-00:00:00.000\0017=2\00116=2\00110=000\001";
        serverOut.write(resendRequest.getBytes(StandardCharsets.UTF_8));
        serverOut.flush();
        TimeUnit.MILLISECONDS.sleep(150);

        synchronized (clientMessages) {
            assertThat(clientMessages.stream().anyMatch(m ->
                    m.contains("35=4") && m.contains("123=Y") && m.contains("36=3"))).isTrue();
        }
    }

    private static OrderView sampleOrder(UUID id, BigDecimal limitPrice) {
        ListingSnapshot listing = new ListingSnapshot(1L, 1, "AAPL", "Apple Inc", "AAPL", "XNAS", "Exchange", "US", "USD",
                new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("150.00"), new BigDecimal("150.00"));
        return new OrderView(id, 1L, "user-1", "desk-1", listing,
                OrderSide.BUY, OrderType.LIMIT, new BigDecimal("100"), limitPrice, new BigDecimal("100"),
                BigDecimal.ZERO, BigDecimal.ZERO, OrderStatus.LIVE, OrderStatus.LIVE, "DMA", "orig-1",
                null, null, null, null, Instant.now(), Instant.now());
    }

    private static final class RecordingCommands extends ExecutionCommandPublisher {
        private final Queue<UUID> fills = new ArrayDeque<>();
        private final Queue<UUID> rejections = new ArrayDeque<>();
        private final Queue<UUID> cancellations = new ArrayDeque<>();

        private RecordingCommands() {
            super(new SimpleMeterRegistry(), ObservationRegistry.NOOP);
        }

        @Override
        void fill(UUID orderId, String deskId, String reference, BigDecimal quantity, BigDecimal price, String venue, Instant occurredAt) {
            fills.add(orderId);
        }

        @Override
        void reject(UUID orderId, String deskId, String reference, String venue, String detail) {
            rejections.add(orderId);
        }

        @Override
        void venueCancel(UUID orderId, String deskId, String reference, String venue, String detail) {
            cancellations.add(orderId);
        }
    }
}
