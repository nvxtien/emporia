package com.emporia.execution;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.TradingEvents.OrderView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixExecutionVenueGatewayTest {
    private ServerSocket serverSocket;
    private int port;
    private Thread serverThread;
    private final RecordingCommands commands = new RecordingCommands();
    private FixExecutionVenueGateway gateway;
    private final Queue<String> clientMessages = new ArrayDeque<>();
    private volatile OutputStream serverOut;

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
                        String reply = "8=FIX.4.2\0019=60\00135=A\00149=EXCHANGE\00156=CLIENT\00134=1\00152=20260727-00:00:00.000\00198=0\001108=30\00110=000\001";
                        out.write(reply.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                }
            } catch (Exception ignored) {
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        gateway = new FixExecutionVenueGateway("XNAS=127.0.0.1:" + port + ":CLIENT:EXCHANGE", commands);
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
        assertThatThrownBy(() -> new FixExecutionVenueGateway("XNAS=invalid", commands))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MIC=host:port:senderCompId:targetCompId");
    }

    @Test
    void submitThrowsWhenSessionNotFound() {
        FixExecutionVenueGateway emptyGateway = new FixExecutionVenueGateway("", commands);
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
        String fillReport = "8=FIX.4.2\0019=120\00135=8\00149=EXCHANGE\00156=CLIENT\00134=2\00152=20260727-00:00:00.000\00111="
                + order.id() + "\00135=8\001150=2\00139=2\00132=10\00131=150.00\00117=exec-99\00110=000\001";
        serverOut.write(fillReport.getBytes(StandardCharsets.UTF_8));
        serverOut.flush();

        TimeUnit.MILLISECONDS.sleep(100);
        assertThat(commands.fills).hasSize(1);

        // Send execution report cancel
        String cancelReport = "8=FIX.4.2\0019=120\00135=8\00149=EXCHANGE\00156=CLIENT\00134=3\00152=20260727-00:00:00.000\00111="
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
        String rejectReport = "8=FIX.4.2\0019=120\00135=8\00149=EXCHANGE\00156=CLIENT\00134=4\00152=20260727-00:00:00.000\00111="
                + order.id() + "\00135=8\001150=8\00139=8\00158=Price out of bounds\00117=exec-101\00110=000\001";
        serverOut.write(rejectReport.getBytes(StandardCharsets.UTF_8));
        serverOut.flush();

        TimeUnit.MILLISECONDS.sleep(100);
        assertThat(commands.rejections).hasSize(1);
    }

    @Test
    void modifyAndCancelThrowWhenSessionNotFound() {
        FixExecutionVenueGateway emptyGateway = new FixExecutionVenueGateway("", commands);
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
            super(null, "executions", new SimpleMeterRegistry());
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
