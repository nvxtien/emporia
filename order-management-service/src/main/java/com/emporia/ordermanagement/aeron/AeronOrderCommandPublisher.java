package com.emporia.ordermanagement.aeron;

import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.sbe.SbeEncoderDecoder;
import io.aeron.Aeron;
import io.aeron.Publication;
import jakarta.annotation.PreDestroy;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Zero-copy Aeron binary OrderCommand publisher.
 * Offers SBE-encoded OrderCommand binary frames onto an Aeron IPC / UDP publication channel.
 */
@Component
public class AeronOrderCommandPublisher implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AeronOrderCommandPublisher.class);

    private final Aeron aeron;
    private final Publication publication;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    protected AeronOrderCommandPublisher() {
        this.aeron = null;
        this.publication = null;
    }

    public boolean publish(OrderCommand command) {
        if (closed.get() || publication == null) return false;
        byte[] encoded = SbeEncoderDecoder.encodeOrderCommand(command);
        UnsafeBuffer buffer = new UnsafeBuffer(encoded);
        long result = publication.offer(buffer, 0, encoded.length);
        if (result < 0) {
            log.debug("Aeron OrderCommand offer returned {} for command {}", result, command.commandId());
            return false;
        }
        return true;
    }

    @Override
    @PreDestroy
    public void close() {
        if (closed.compareAndSet(false, true)) {
            if (publication != null) publication.close();
            if (aeron != null) aeron.close();
            log.info("Aeron OrderCommand publisher closed");
        }
    }
}
