package com.emporia.execution;

import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.TradingEvents.OrderView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@ConditionalOnProperty(name = "emporia.execution.venue-mode", havingValue = "fix")
class FixExecutionVenueGateway implements ExecutionVenueGateway, SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(FixExecutionVenueGateway.class);
    private static final DateTimeFormatter FIX_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private final Map<String, FixSession> sessions;
    private final Map<UUID, OrderView> orders = new ConcurrentHashMap<>();
    private final Map<UUID, String> currentClientOrderIds = new ConcurrentHashMap<>();
    private final Map<String, UUID> clientOrderIds = new ConcurrentHashMap<>();
    private final ExecutionCommandPublisher commands;
    private final AtomicBoolean running = new AtomicBoolean();

    FixExecutionVenueGateway(@Value("${emporia.execution.fix-venues:}") String definitions,
                             ExecutionCommandPublisher commands) {
        this.commands = commands;
        Map<String, FixSession> parsed = new LinkedHashMap<>();
        if (definitions != null && !definitions.isBlank()) {
            for (String definition : definitions.split(",")) {
                String[] entry = definition.strip().split("=", 2);
                String[] connection = entry.length == 2 ? entry[1].split(":", 4) : new String[0];
                if (connection.length != 4) {
                    throw new IllegalArgumentException(
                            "FIX_EXECUTION_VENUES entries must use MIC=host:port:senderCompId:targetCompId");
                }
                String mic = entry[0].strip().toUpperCase(java.util.Locale.ROOT);
                parsed.put(mic, new FixSession(mic, connection[0], Integer.parseInt(connection[1]),
                        connection[2], connection[3], this::onMessage));
            }
        }
        this.sessions = Map.copyOf(parsed);
    }

    @Override
    public void submit(OrderView order) {
        FixSession session = session(order);
        remember(order, order.id().toString());
        LinkedHashMap<Integer, String> fields = commonOrderFields(order, order.id().toString());
        session.send("D", fields);
    }

    @Override
    public void modify(OrderView order) {
        FixSession session = session(order);
        String original = currentClientOrderIds.getOrDefault(order.id(), order.id().toString());
        String replacement = order.id() + ":M:" + order.version();
        remember(order, replacement);
        LinkedHashMap<Integer, String> fields = commonOrderFields(order, replacement);
        fields.put(41, original);
        session.send("G", fields);
    }

    @Override
    public void cancel(OrderView order) {
        FixSession session = session(order);
        String original = currentClientOrderIds.getOrDefault(order.id(), order.id().toString());
        String cancellation = order.id() + ":C:" + order.version();
        remember(order, cancellation);
        LinkedHashMap<Integer, String> fields = new LinkedHashMap<>();
        fields.put(11, cancellation);
        fields.put(41, original);
        fields.put(55, order.listing().marketSymbol());
        fields.put(54, order.side() == OrderSide.BUY ? "1" : "2");
        fields.put(60, FIX_TIME.format(Instant.now()));
        session.send("F", fields);
    }

    @Override
    public void recover(OrderView order) {
        // ClOrdID is the persisted Emporia order id for the original submit.
        // Rebuilding these maps allows later execution reports and cancel
        // requests to correlate without duplicating the NewOrderSingle.
        remember(order, order.id().toString());
    }

    private void remember(OrderView order, String clientOrderId) {
        orders.put(order.id(), order);
        currentClientOrderIds.put(order.id(), clientOrderId);
        clientOrderIds.put(clientOrderId, order.id());
    }

    private LinkedHashMap<Integer, String> commonOrderFields(OrderView order, String clientOrderId) {
        LinkedHashMap<Integer, String> fields = new LinkedHashMap<>();
        fields.put(11, clientOrderId);
        fields.put(55, order.listing().marketSymbol());
        fields.put(54, order.side() == OrderSide.BUY ? "1" : "2");
        fields.put(38, order.remainingQuantity().stripTrailingZeros().toPlainString());
        fields.put(40, order.type() == OrderType.MARKET ? "1" : "2");
        if (order.type() == OrderType.LIMIT) {
            fields.put(44, order.limitPrice().stripTrailingZeros().toPlainString());
        }
        fields.put(59, "0");
        fields.put(60, FIX_TIME.format(Instant.now()));
        return fields;
    }

    private FixSession session(OrderView order) {
        FixSession session = sessions.get(order.listing().exchangeMic().toUpperCase(java.util.Locale.ROOT));
        if (session == null) {
            throw new IllegalStateException("No FIX execution session is configured for "
                    + order.listing().exchangeMic());
        }
        return session;
    }

    private void onMessage(String mic, Map<Integer, String> fields) {
        if (!"8".equals(fields.get(35))) return;
        UUID orderId = clientOrderIds.get(fields.get(11));
        if (orderId == null && fields.get(41) != null) orderId = clientOrderIds.get(fields.get(41));
        OrderView order = orderId == null ? null : orders.get(orderId);
        if (order == null) {
            log.warn("Ignoring FIX execution report with unknown ClOrdID {}", fields.get(11));
            return;
        }

        String executionType = fields.get(150);
        String orderStatus = fields.get(39);
        String executionReference = fields.getOrDefault(17, mic + "-" + fields.getOrDefault(11, order.id().toString()));
        if (fields.get(32) != null && fields.get(31) != null) {
            java.math.BigDecimal lastQuantity = new java.math.BigDecimal(fields.get(32));
            java.math.BigDecimal lastPrice = new java.math.BigDecimal(fields.get(31));
            if (lastQuantity.signum() > 0) {
                commands.fill(order.id(), order.deskId(), executionReference, lastQuantity, lastPrice, mic, Instant.now());
            }
        }
        if ("8".equals(executionType) || "8".equals(orderStatus)) {
            commands.reject(order.id(), order.deskId(), executionReference, mic,
                    fields.getOrDefault(58, "FIX venue rejected the order"));
        } else if ("4".equals(executionType) || "4".equals(orderStatus)) {
            commands.venueCancel(order.id(), order.deskId(), executionReference, mic,
                    fields.getOrDefault(58, "FIX venue cancelled the order"));
        }
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        if (sessions.isEmpty()) {
            running.set(false);
            throw new IllegalStateException("FIX execution mode requires FIX_EXECUTION_VENUES");
        }
        sessions.values().forEach(FixSession::start);
    }

    @Override
    public void stop() {
        running.set(false);
        sessions.values().forEach(FixSession::stop);
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private static final class FixSession {
        private static final byte SOH = 1;
        private final String mic;
        private final String host;
        private final int port;
        private final String senderCompId;
        private final String targetCompId;
        private final MessageSink sink;
        private final AtomicBoolean running = new AtomicBoolean();
        private final AtomicInteger outgoingSequence = new AtomicInteger(1);
        private final ExecutorService executor;
        private volatile Socket socket;
        private volatile OutputStream output;

        private FixSession(String mic, String host, int port, String senderCompId, String targetCompId,
                           MessageSink sink) {
            this.mic = mic;
            this.host = host;
            this.port = port;
            this.senderCompId = senderCompId;
            this.targetCompId = targetCompId;
            this.sink = sink;
            this.executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "fix-" + mic.toLowerCase(java.util.Locale.ROOT));
                thread.setDaemon(true);
                return thread;
            });
        }

        void start() {
            if (running.compareAndSet(false, true)) executor.submit(this::connectLoop);
        }

        void stop() {
            running.set(false);
            close();
            executor.shutdownNow();
        }

        synchronized void send(String messageType, LinkedHashMap<Integer, String> fields) {
            if (output == null) throw new IllegalStateException("FIX session " + mic + " is disconnected");
            try {
                output.write(encode(messageType, fields));
                output.flush();
            } catch (Exception exception) {
                close();
                throw new IllegalStateException("Unable to send FIX message to " + mic, exception);
            }
        }

        private void connectLoop() {
            while (running.get()) {
                try (Socket connected = new Socket()) {
                    connected.connect(new InetSocketAddress(host, port), 5_000);
                    connected.setKeepAlive(true);
                    connected.setTcpNoDelay(true);
                    socket = connected;
                    output = connected.getOutputStream();
                    send("A", linkedFields(98, "0", 108, "30", 1137, "9"));
                    log.info("Connected FIX execution session {} to {}:{}", mic, host, port);
                    read(connected.getInputStream());
                } catch (Exception exception) {
                    if (running.get()) log.warn("FIX execution session {} disconnected: {}", mic, exception.getMessage());
                } finally {
                    close();
                }
                if (running.get()) {
                    try {
                        Thread.sleep(1_000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        private void read(InputStream input) throws Exception {
            ByteArrayOutputStream buffered = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            while (running.get()) {
                int count = input.read(chunk);
                if (count < 0) return;
                buffered.write(chunk, 0, count);
                byte[] bytes = buffered.toByteArray();
                int boundary = messageBoundary(bytes);
                while (boundary >= 0) {
                    byte[] message = Arrays.copyOfRange(bytes, 0, boundary);
                    handle(message);
                    bytes = Arrays.copyOfRange(bytes, boundary, bytes.length);
                    boundary = messageBoundary(bytes);
                }
                buffered.reset();
                buffered.write(bytes);
            }
        }

        private void handle(byte[] message) {
            Map<Integer, String> fields = parse(message);
            String messageType = fields.get(35);
            if ("0".equals(messageType)) return;
            if ("1".equals(messageType)) {
                LinkedHashMap<Integer, String> heartbeat = new LinkedHashMap<>();
                if (fields.get(112) != null) heartbeat.put(112, fields.get(112));
                send("0", heartbeat);
            } else if ("5".equals(messageType)) {
                running.set(false);
            } else {
                sink.accept(mic, fields);
            }
        }

        private byte[] encode(String messageType, LinkedHashMap<Integer, String> fields) {
            StringBuilder body = new StringBuilder();
            append(body, 35, messageType);
            append(body, 34, String.valueOf(outgoingSequence.getAndIncrement()));
            append(body, 49, senderCompId);
            append(body, 56, targetCompId);
            append(body, 52, FIX_TIME.format(Instant.now()));
            fields.forEach((tag, value) -> append(body, tag, value));
            byte[] bodyBytes = body.toString().getBytes(StandardCharsets.US_ASCII);
            String header = "8=FIXT.1.1\u00019=" + bodyBytes.length + "\u0001";
            byte[] withoutChecksum = (header + body).getBytes(StandardCharsets.US_ASCII);
            int checksum = 0;
            for (byte value : withoutChecksum) checksum = (checksum + Byte.toUnsignedInt(value)) % 256;
            String trailer = "10=%03d\u0001".formatted(checksum);
            byte[] result = Arrays.copyOf(withoutChecksum, withoutChecksum.length + trailer.length());
            System.arraycopy(trailer.getBytes(StandardCharsets.US_ASCII), 0, result, withoutChecksum.length,
                    trailer.length());
            return result;
        }

        private static int messageBoundary(byte[] bytes) {
            for (int index = 0; index <= bytes.length - 7; index++) {
                if ((index == 0 || bytes[index - 1] == SOH)
                        && bytes[index] == '1' && bytes[index + 1] == '0' && bytes[index + 2] == '='
                        && bytes[index + 6] == SOH) {
                    return index + 7;
                }
            }
            return -1;
        }

        private static Map<Integer, String> parse(byte[] message) {
            Map<Integer, String> fields = new LinkedHashMap<>();
            for (String field : new String(message, StandardCharsets.US_ASCII).split("\\u0001")) {
                int separator = field.indexOf('=');
                if (separator > 0) fields.put(Integer.parseInt(field.substring(0, separator)),
                        field.substring(separator + 1));
            }
            return fields;
        }

        private static void append(StringBuilder target, int tag, String value) {
            target.append(tag).append('=').append(value).append((char) SOH);
        }

        private synchronized void close() {
            output = null;
            Socket current = socket;
            socket = null;
            if (current != null) {
                try (current) {
                    // Closing the current session releases its input and output streams.
                } catch (Exception ignored) {
                    // Connection is already being discarded.
                }
            }
        }

        private static LinkedHashMap<Integer, String> linkedFields(Object... values) {
            LinkedHashMap<Integer, String> fields = new LinkedHashMap<>();
            for (int index = 0; index < values.length; index += 2) {
                fields.put((Integer) values[index], (String) values[index + 1]);
            }
            return fields;
        }
    }

    @FunctionalInterface
    private interface MessageSink {
        void accept(String mic, Map<Integer, String> fields);
    }
}
