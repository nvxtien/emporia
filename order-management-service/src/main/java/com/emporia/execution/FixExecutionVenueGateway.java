package com.emporia.execution;

import com.emporia.execution.fix.FixCodec;
import com.emporia.execution.fix.FixSequenceState;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.TradingEvents.OrderView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
class FixExecutionVenueGateway implements ExecutionVenueGateway, SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(FixExecutionVenueGateway.class);

    private final Map<String, FixSession> sessions;
    private final Map<UUID, OrderView> orders = new ConcurrentHashMap<>();
    private final Map<UUID, String> currentClientOrderIds = new ConcurrentHashMap<>();
    private final Map<String, UUID> clientOrderIds = new ConcurrentHashMap<>();
    private final ExecutionCommandPublisher commands;
    private final AtomicBoolean running = new AtomicBoolean();
    /** Whether this gateway is the one emporia.execution.venue-mode selects. */
    private final boolean selected;

    FixExecutionVenueGateway(String definitions, ExecutionCommandPublisher commands,
                             FixSessionStateStore sessionState, FixMessageLogStore messageLog) {
        // The test seam behaves as the selected venue, which is what every
        // existing test assumes and asserts.
        this(definitions, commands, sessionState, messageLog, false, "", "", "", "", "fix");
    }

    /**
     * Pre-existing nine-argument form, kept so the TLS test compiles unchanged.
     * Behaves as the selected venue, which is what that test asserts.
     */
    FixExecutionVenueGateway(String definitions, ExecutionCommandPublisher commands,
                             FixSessionStateStore sessionState, FixMessageLogStore messageLog,
                             boolean tlsEnabled, String trustStorePath, String trustStorePassword,
                             String keyStorePath, String keyStorePassword) {
        this(definitions, commands, sessionState, messageLog, tlsEnabled, trustStorePath,
                trustStorePassword, keyStorePath, keyStorePassword, "fix");
    }

    @Autowired
    FixExecutionVenueGateway(@Value("${emporia.execution.fix-venues:}") String definitions,
                             ExecutionCommandPublisher commands, FixSessionStateStore sessionState,
                             FixMessageLogStore messageLog,
                             @Value("${emporia.execution.fix-tls-enabled:false}") boolean tlsEnabled,
                             @Value("${emporia.execution.fix-tls-truststore-path:}") String trustStorePath,
                             @Value("${emporia.execution.fix-tls-truststore-password:}") String trustStorePassword,
                             @Value("${emporia.execution.fix-tls-keystore-path:}") String keyStorePath,
                             @Value("${emporia.execution.fix-tls-keystore-password:}") String keyStorePassword,
                             @Value("${emporia.execution.venue-mode:exchange-core}") String configuredVenueMode) {
        this.selected = "fix".equals(configuredVenueMode == null ? ""
                : configuredVenueMode.strip().toLowerCase(java.util.Locale.ROOT));
        this.commands = commands;
        SSLSocketFactory tlsSocketFactory = tlsEnabled
                ? buildTlsSocketFactory(trustStorePath, trustStorePassword, keyStorePath, keyStorePassword)
                : null;
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
                        connection[2], connection[3], this::onMessage, sessionState, messageLog, tlsSocketFactory));
            }
        }
        this.sessions = Map.copyOf(parsed);
    }

    // No explicit truststore uses the JVM's default trust store (public CAs), which is
    // correct for a venue with a publicly trusted certificate. An internal/private CA
    // requires FIX_TLS_TRUSTSTORE_PATH. The keystore is only needed for mutual TLS.
    private static SSLSocketFactory buildTlsSocketFactory(String trustStorePath, String trustStorePassword,
                                                           String keyStorePath, String keyStorePassword) {
        try {
            TrustManagerFactory trustManagerFactory = null;
            if (trustStorePath != null && !trustStorePath.isBlank()) {
                KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
                try (FileInputStream in = new FileInputStream(trustStorePath)) {
                    trustStore.load(in, trustStorePassword == null ? null : trustStorePassword.toCharArray());
                }
                trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trustManagerFactory.init(trustStore);
            }
            KeyManagerFactory keyManagerFactory = null;
            if (keyStorePath != null && !keyStorePath.isBlank()) {
                KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
                try (FileInputStream in = new FileInputStream(keyStorePath)) {
                    keyStore.load(in, keyStorePassword == null ? null : keyStorePassword.toCharArray());
                }
                keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManagerFactory.init(keyStore, keyStorePassword == null ? null : keyStorePassword.toCharArray());
            }
            SSLContext context = SSLContext.getInstance("TLSv1.3");
            context.init(keyManagerFactory == null ? null : keyManagerFactory.getKeyManagers(),
                    trustManagerFactory == null ? null : trustManagerFactory.getTrustManagers(), null);
            return context.getSocketFactory();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to initialize FIX TLS configuration", exception);
        }
    }

    @Override
    public String venueMode() {
        return "fix";
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
        fields.put(60, FixCodec.timestamp(Instant.now()));
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
        fields.put(60, FixCodec.timestamp(Instant.now()));
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
            if (selected) {
                running.set(false);
                throw new IllegalStateException(
                        "emporia.execution.venue-mode=fix requires FIX_EXECUTION_VENUES");
            }
            // The gateways stopped excluding each other, so this bean now exists
            // in deployments that route elsewhere. With nothing configured it has
            // nothing to connect to and stays inert, rather than failing a startup
            // that has no use for it. Deleting the throw outright would have lost
            // the protection above, where fix IS the selected venue and an empty
            // venue list means orders reach nowhere at all.
            log.info("FIX venue gateway idle: no FIX_EXECUTION_VENUES and venue-mode is not fix");
            return;
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
        private static final int HEARTBEAT_INTERVAL_SECONDS = 30;
        private static final int POLL_TIMEOUT_MS = 5_000;
        // NewOrderSingle, OrderCancelReplaceRequest, OrderCancelRequest: the only
        // message types worth persisting for resend. Admin/session messages are
        // never individually replayed; a gap of those is bridged with a GapFill.
        private static final Set<String> APPLICATION_MESSAGE_TYPES = Set.of("D", "G", "F");
        private final String mic;
        private final String host;
        private final int port;
        private final String senderCompId;
        private final String targetCompId;
        private final MessageSink sink;
        private final FixMessageLogStore messageLog;
        private final AtomicBoolean running = new AtomicBoolean();
        private final FixSequenceState sequence;
        private final AtomicBoolean loggingOut = new AtomicBoolean();
        private final AtomicReference<CountDownLatch> logoutAcknowledged = new AtomicReference<>();
        // Out-of-order arrivals held until the gap ahead of them is filled. Only
        // ever touched from the single read-loop executor thread, so a plain
        // TreeMap (not a concurrent one) is safe.
        private final TreeMap<Integer, Map<Integer, String>> pendingMessages = new TreeMap<>();
        private final ExecutorService executor;
        private final SSLSocketFactory tlsSocketFactory;
        private volatile Socket socket;
        private volatile OutputStream output;
        private boolean resendRequestedForCurrentGap;

        private FixSession(String mic, String host, int port, String senderCompId, String targetCompId,
                           MessageSink sink, FixSessionStateStore sessionState, FixMessageLogStore messageLog,
                           SSLSocketFactory tlsSocketFactory) {
            this.mic = mic;
            this.host = host;
            this.port = port;
            this.senderCompId = senderCompId;
            this.targetCompId = targetCompId;
            this.sink = sink;
            this.sequence = new FixSequenceState(mic, sessionState);
            this.messageLog = messageLog;
            this.tlsSocketFactory = tlsSocketFactory;
            this.executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "fix-" + mic.toLowerCase(java.util.Locale.ROOT));
                thread.setDaemon(true);
                return thread;
            });
        }

        // Unconnected: the caller connects with an explicit timeout, same as a
        // plain Socket, then triggers the TLS handshake separately.
        private Socket openSocket() throws IOException {
            if (tlsSocketFactory == null) return new Socket();
            SSLSocket sslSocket = (SSLSocket) tlsSocketFactory.createSocket();
            SSLParameters parameters = sslSocket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            sslSocket.setSSLParameters(parameters);
            return sslSocket;
        }

        void start() {
            if (running.compareAndSet(false, true)) executor.submit(this::connectLoop);
        }

        void stop() {
            if (running.compareAndSet(true, false)) sendLogoutAndAwaitAcknowledgement();
            close();
            executor.shutdownNow();
        }

        // Sends a Logout and waits briefly for the counterparty's Logout reply
        // before tearing down the socket, rather than disconnecting abruptly.
        // Best-effort: a missing or slow reply still falls through to close().
        private void sendLogoutAndAwaitAcknowledgement() {
            if (output == null) return;
            CountDownLatch latch = new CountDownLatch(1);
            logoutAcknowledged.set(latch);
            loggingOut.set(true);
            try {
                send("5", new LinkedHashMap<>());
                latch.await(2, TimeUnit.SECONDS);
            } catch (Exception exception) {
                log.warn("FIX session {} Logout handshake did not complete cleanly: {}", mic, exception.getMessage());
            } finally {
                loggingOut.set(false);
            }
        }

        synchronized void send(String messageType, LinkedHashMap<Integer, String> fields) {
            int seqNum = sequence.claimOutgoing();
            Instant sentAt = Instant.now();
            if (APPLICATION_MESSAGE_TYPES.contains(messageType)) {
                // Logged before the write for the same reason: if the counterparty
                // never actually received this, a future resend must still be able
                // to hand it back, and there is no cheap way to un-log it otherwise.
                messageLog.record(mic, seqNum, messageType, fields, sentAt);
            }
            rawSend(messageType, seqNum, sentAt, fields);
        }

        // Writes a message with an explicit sequence number and sending time,
        // used both for normal sends and for verbatim resends/GapFills, which
        // must reuse the original sequence number rather than consume a new one.
        private synchronized void rawSend(String messageType, int seqNum, Instant sendingTime,
                                          LinkedHashMap<Integer, String> fields) {
            if (output == null) throw new IllegalStateException("FIX session " + mic + " is disconnected");
            try {
                output.write(FixCodec.encode(messageType, seqNum, sendingTime, senderCompId, targetCompId, fields));
                output.flush();
            } catch (Exception exception) {
                close();
                throw new IllegalStateException("Unable to send FIX message to " + mic, exception);
            }
        }

        // Retransmits a previously logged application message with PossDupFlag
        // set and the original SendingTime preserved as OrigSendingTime, using
        // its original sequence number.
        private void resendLogged(FixMessageLogStore.LoggedMessage message) {
            LinkedHashMap<Integer, String> fields = new LinkedHashMap<>(message.fields());
            fields.put(43, "Y");
            fields.put(122, FixCodec.timestamp(message.sentAt()));
            rawSend(message.msgType(), message.seqNum(), Instant.now(), fields);
        }

        // Bridges a range of un-logged (admin/session) outgoing sequence numbers
        // with a single SequenceReset-GapFill instead of replaying them, which is
        // meaningless for messages like heartbeats. fromSeq is the sequence number
        // the gap starts at; newSeqNo is what the counterparty should expect next.
        private void sendGapFill(int fromSeq, int newSeqNo) {
            LinkedHashMap<Integer, String> fields = new LinkedHashMap<>();
            fields.put(123, "Y");
            fields.put(36, String.valueOf(newSeqNo));
            rawSend("4", fromSeq, Instant.now(), fields);
        }

        // Answers a counterparty ResendRequest by walking the requested range:
        // logged application messages are replayed verbatim, and any gaps
        // (admin/session messages we never logged) are bridged with GapFills.
        // Synchronized so "through current" (EndSeqNo=0) resolves against a
        // snapshot of outgoingSequence that can't be invalidated by a concurrent
        // send() from another thread (order commands arrive on a dispatcher
        // shard thread, independent of this session's own read-loop thread).
        private synchronized void handleResendRequest(Map<Integer, String> fields) {
            int beginSeqNo = FixCodec.integer(fields, 7, 1);
            int endSeqNoRaw = FixCodec.integer(fields, 16, 0);
            int endSeqNo = endSeqNoRaw == 0 ? sequence.lastOutgoing() : endSeqNoRaw;
            if (endSeqNo < beginSeqNo) return;
            List<FixMessageLogStore.LoggedMessage> logged = messageLog.range(mic, beginSeqNo, endSeqNo);
            Map<Integer, FixMessageLogStore.LoggedMessage> bySeq = new HashMap<>();
            logged.forEach(message -> bySeq.put(message.seqNum(), message));

            int gapStart = -1;
            for (int seq = beginSeqNo; seq <= endSeqNo; seq++) {
                FixMessageLogStore.LoggedMessage message = bySeq.get(seq);
                if (message == null) {
                    if (gapStart == -1) gapStart = seq;
                    continue;
                }
                if (gapStart != -1) {
                    sendGapFill(gapStart, seq);
                    gapStart = -1;
                }
                resendLogged(message);
            }
            if (gapStart != -1) sendGapFill(gapStart, endSeqNo + 1);
        }

        // Asks the counterparty to resend a range we noticed is missing. Uses the
        // normal send() path: a ResendRequest is itself a new outgoing message
        // with its own sequence number, not a replay.
        private void requestResend(int fromSeq, int toSeq) {
            LinkedHashMap<Integer, String> fields = new LinkedHashMap<>();
            fields.put(7, String.valueOf(fromSeq));
            fields.put(16, String.valueOf(toSeq));
            send("2", fields);
        }


        private void connectLoop() {
            while (running.get()) {
                try (Socket connected = openSocket()) {
                    connected.connect(new InetSocketAddress(host, port), 5_000);
                    connected.setKeepAlive(true);
                    connected.setTcpNoDelay(true);
                    if (connected instanceof SSLSocket) {
                        connected.setSoTimeout(10_000);
                        ((SSLSocket) connected).startHandshake();
                    }
                    socket = connected;
                    output = connected.getOutputStream();
                    FixSessionStateStore.SessionState state = sequence.restoreForToday();
                    pendingMessages.clear();
                    resendRequestedForCurrentGap = false;
                    if (state.freshSession()) {
                        // Sequence numbers restart at 1 on a new session day, so
                        // yesterday's log would collide on (mic, seq_num) and is
                        // meaningless for today's resend requests anyway.
                        messageLog.clear(mic);
                    }
                    LinkedHashMap<Integer, String> logon = FixCodec.fields(98, "0",
                            108, String.valueOf(HEARTBEAT_INTERVAL_SECONDS), 1137, "9");
                    if (state.freshSession()) logon.put(141, "Y");
                    send("A", logon);
                    log.info("Connected FIX execution session {} to {}:{} (outgoing={}, incoming={}, fresh={})",
                            mic, host, port, state.outgoingSeqNum(), state.incomingSeqNum(), state.freshSession());
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
            socket.setSoTimeout(POLL_TIMEOUT_MS);
            Instant lastReceivedAt = Instant.now();
            boolean testRequestSent = false;
            ByteArrayOutputStream buffered = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            while (running.get()) {
                int count;
                try {
                    count = input.read(chunk);
                } catch (SocketTimeoutException timeout) {
                    // No SO_TIMEOUT-driven poll produced traffic; decide whether the
                    // silence is still within HeartBtInt tolerance, warrants a
                    // TestRequest, or means the connection is dead.
                    Duration silence = Duration.between(lastReceivedAt, Instant.now());
                    if (silence.compareTo(Duration.ofSeconds(HEARTBEAT_INTERVAL_SECONDS * 2L)) > 0) {
                        log.warn("FIX session {} missed heartbeats for {}s; reconnecting", mic, silence.toSeconds());
                        return;
                    }
                    if (!testRequestSent
                            && silence.compareTo(Duration.ofSeconds(HEARTBEAT_INTERVAL_SECONDS + 10L)) > 0) {
                        LinkedHashMap<Integer, String> testRequest = new LinkedHashMap<>();
                        testRequest.put(112, "TEST-" + System.currentTimeMillis());
                        send("1", testRequest);
                        testRequestSent = true;
                    }
                    continue;
                }
                if (count < 0) return;
                lastReceivedAt = Instant.now();
                testRequestSent = false;
                buffered.write(chunk, 0, count);
                byte[] bytes = buffered.toByteArray();
                int boundary = FixCodec.messageBoundary(bytes);
                while (boundary >= 0) {
                    byte[] message = Arrays.copyOfRange(bytes, 0, boundary);
                    onIncomingMessage(message);
                    bytes = Arrays.copyOfRange(bytes, boundary, bytes.length);
                    boundary = FixCodec.messageBoundary(bytes);
                }
                buffered.reset();
                buffered.write(bytes);
            }
        }

        // Entry point for every parsed inbound message. Compares its MsgSeqNum
        // against what's expected before any message-type handling runs: an
        // out-of-order arrival is buffered and triggers a ResendRequest instead
        // of being processed immediately, so a lost message can never be
        // silently skipped over.
        private void onIncomingMessage(byte[] message) {
            Map<Integer, String> fields = FixCodec.parse(message);
            Integer seqNum = FixCodec.integerOrNull(fields.get(34));
            if (seqNum == null) {
                // No usable MsgSeqNum to compare; handle it directly rather than
                // dropping a message we can't place in the sequence at all.
                handle(fields);
                return;
            }

            int expected = sequence.expectedIncoming();
            if (seqNum < expected) {
                log.debug("FIX session {} ignoring MsgSeqNum {} (already at {})", mic, seqNum, expected);
                return;
            }
            if (seqNum > expected) {
                boolean gapAlreadyOpen = !pendingMessages.isEmpty();
                pendingMessages.put(seqNum, fields);
                if (!gapAlreadyOpen && !resendRequestedForCurrentGap) {
                    resendRequestedForCurrentGap = true;
                    requestResend(expected, seqNum - 1);
                }
                return;
            }

            advanceAndHandle(seqNum, fields);
            // Draining here (rather than only on ResendRequest replies) means a
            // gap that closes because of ordinary in-order traffic - not just a
            // resend - still releases whatever was buffered behind it.
            while (true) {
                Map.Entry<Integer, Map<Integer, String>> next = pendingMessages.firstEntry();
                if (next == null || next.getKey() != sequence.expectedIncoming()) break;
                pendingMessages.remove(next.getKey());
                advanceAndHandle(next.getKey(), next.getValue());
            }
            if (pendingMessages.isEmpty()) resendRequestedForCurrentGap = false;
        }

        private void advanceAndHandle(int seqNum, Map<Integer, String> fields) {
            sequence.acceptIncoming(seqNum);
            handle(fields);
        }

        private void handle(Map<Integer, String> fields) {
            String messageType = fields.get(35);
            if ("0".equals(messageType)) return;
            if ("1".equals(messageType)) {
                LinkedHashMap<Integer, String> heartbeat = new LinkedHashMap<>();
                if (fields.get(112) != null) heartbeat.put(112, fields.get(112));
                send("0", heartbeat);
            } else if ("5".equals(messageType)) {
                if (loggingOut.get()) {
                    CountDownLatch latch = logoutAcknowledged.get();
                    if (latch != null) latch.countDown();
                } else {
                    // Unsolicited Logout: acknowledge with our own before disconnecting,
                    // rather than dropping the connection without a reply.
                    try {
                        send("5", new LinkedHashMap<>());
                    } catch (Exception replyFailed) {
                        log.warn("FIX session {} could not send a Logout reply: {}", mic, replyFailed.getMessage());
                    }
                }
                running.set(false);
            } else if ("3".equals(messageType)) {
                log.error("FIX session {} received a session-level Reject: reason={} refSeqNum={} text={}",
                        mic, fields.get(373), fields.get(45), fields.get(58));
            } else if ("2".equals(messageType)) {
                handleResendRequest(fields);
            } else if ("4".equals(messageType)) {
                // SequenceReset (plain or GapFill): the counterparty is telling us
                // to jump ahead, typically in response to our own ResendRequest
                // skipping over admin messages it never logged either. A reset
                // can only move forward - a lower NewSeqNo would regress our
                // expectation and make later, legitimate messages look like gaps.
                Integer newSeqNo = FixCodec.integerOrNull(fields.get(36));
                if (newSeqNo != null) sequence.resetIncomingForward(newSeqNo);
            } else {
                sink.accept(mic, fields);
            }
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

    }

    @FunctionalInterface
    private interface MessageSink {
        void accept(String mic, Map<Integer, String> fields);
    }
}
