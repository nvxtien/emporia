package com.emporia.execution;

import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderStatus;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.TradingEvents.OrderView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the FIX gateway can complete a real TLS handshake and exchange FIX
 * messages over it, using a self-signed certificate under
 * src/test/resources/fix-tls (generated with keytool, not checked-in secrets).
 */
class FixExecutionVenueGatewayTlsTest {
    private static final String STORE_PASSWORD = "changeit";

    private SSLServerSocket serverSocket;
    private Thread serverThread;
    private final Queue<String> clientMessages = new ArrayDeque<>();
    private FixExecutionVenueGateway gateway;
    private final AtomicInteger serverSeq = new AtomicInteger();

    private int nextServerSeq() {
        return serverSeq.incrementAndGet();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (gateway != null) gateway.stop();
        if (serverSocket != null) serverSocket.close();
    }

    @Test
    void completesTheTlsHandshakeAndSubmitsAnOrder() throws Exception {
        SSLContext serverContext = serverTlsContext();
        SSLServerSocketFactory serverFactory = serverContext.getServerSocketFactory();
        serverSocket = (SSLServerSocket) serverFactory.createServerSocket(0);
        int port = serverSocket.getLocalPort();

        serverThread = new Thread(() -> {
            try {
                SSLSocket socket = (SSLSocket) serverSocket.accept();
                socket.startHandshake();
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                byte[] buf = new byte[4096];
                while (!serverSocket.isClosed()) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    String msg = new String(buf, 0, n, StandardCharsets.UTF_8);
                    synchronized (clientMessages) {
                        clientMessages.add(msg);
                    }
                    if (msg.contains("35=A")) {
                        String reply = "8=FIX.4.2\0019=60\00135=A\00149=EXCHANGE\00156=CLIENT\00134=" + nextServerSeq()
                                + "\00152=20260727-00:00:00.000\00198=0\001108=30\00110=000\001";
                        out.write(reply.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } else if (msg.contains("35=5")) {
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

        String trustStorePath = resourcePath("fix-tls/client-truststore.p12");
        gateway = new FixExecutionVenueGateway("XNAS=127.0.0.1:" + port + ":CLIENT:EXCHANGE",
                new RecordingCommands(), new InMemoryFixSessionStateStore(), new InMemoryFixMessageLogStore(),
                true, trustStorePath, STORE_PASSWORD, "", "");
        gateway.start();
        TimeUnit.MILLISECONDS.sleep(300);

        OrderView order = sampleOrder();
        gateway.submit(order);
        TimeUnit.MILLISECONDS.sleep(150);

        synchronized (clientMessages) {
            assertThat(clientMessages.stream().anyMatch(m -> m.contains("35=A"))).isTrue();
            assertThat(clientMessages.stream().anyMatch(m -> m.contains("35=D"))).isTrue();
        }
    }

    private static SSLContext serverTlsContext() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = FixExecutionVenueGatewayTlsTest.class
                .getResourceAsStream("/fix-tls/server-keystore.p12")) {
            keyStore.load(in, STORE_PASSWORD.toCharArray());
        }
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, STORE_PASSWORD.toCharArray());
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(keyManagerFactory.getKeyManagers(), null, null);
        return context;
    }

    private static String resourcePath(String name) throws Exception {
        var url = FixExecutionVenueGatewayTlsTest.class.getResource("/" + name);
        if (url == null) throw new IllegalStateException("Missing test resource " + name);
        return java.nio.file.Path.of(url.toURI()).toString();
    }

    private static OrderView sampleOrder() {
        ListingSnapshot listing = new ListingSnapshot(1L, 1, "AAPL", "Apple Inc", "AAPL", "XNAS", "Exchange", "US",
                "USD", new BigDecimal("0.01"), BigDecimal.ONE, new BigDecimal("150.00"), new BigDecimal("150.00"));
        return new OrderView(UUID.randomUUID(), 1L, "user-1", "desk-1", listing,
                OrderSide.BUY, OrderType.LIMIT, new BigDecimal("100"), new BigDecimal("150.00"),
                new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO, OrderStatus.LIVE, OrderStatus.LIVE,
                "DMA", "orig-1", null, null, null, null, Instant.now(), Instant.now());
    }

    private static final class RecordingCommands extends ExecutionCommandPublisher {
        private RecordingCommands() {
            super(null, "executions", new SimpleMeterRegistry(), ObservationRegistry.NOOP);
        }
    }
}
