package com.emporia.ordermanagement.aeron;

import com.emporia.events.TradingEvents.CommandType;
import com.emporia.events.TradingEvents.ListingSnapshot;
import com.emporia.events.TradingEvents.OrderCommand;
import com.emporia.events.TradingEvents.OrderSide;
import com.emporia.events.TradingEvents.OrderType;
import com.emporia.events.sbe.SbeEncoderDecoder;
import com.emporia.events.time.DomainClock;
import com.emporia.ordermanagement.disruptor.DisruptorOrderPipeline;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AeronOrderCommandSubscriberTest {

    @Test
    void binaryOrderCommandFragmentIsDecodedAndSubmittedToPipeline() {
        DisruptorOrderPipeline mockPipeline = mock(DisruptorOrderPipeline.class);
        AeronOrderCommandSubscriber subscriber = new AeronOrderCommandSubscriber(mockPipeline);

        UUID commandId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        ListingSnapshot listing = new ListingSnapshot(10L, 1, "AAPL", "Apple Inc.", "AAPL", "XNAS", "NASDAQ", "US", "USD",
                new BigDecimal("0.010000"), new BigDecimal("1.000000"), new BigDecimal("150.000000"), new BigDecimal("149.000000"));

        OrderCommand command = new OrderCommand(1, commandId, CommandType.CREATE, "trader-1", "desk-1",
                DomainClock.now(), orderId, null, listing, OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("100.000000"), new BigDecimal("150.000000"), "DMA", "REF-101", null, Map.of());

        byte[] encoded = SbeEncoderDecoder.encodeOrderCommand(command);
        UnsafeBuffer buffer = new UnsafeBuffer(encoded);

        subscriber.onFragment(buffer, 0, encoded.length);

        ArgumentCaptor<OrderCommand> captor = ArgumentCaptor.forClass(OrderCommand.class);
        verify(mockPipeline).submit(captor.capture());

        OrderCommand received = captor.getValue();
        assertThat(received.commandId()).isEqualTo(commandId);
        assertThat(received.orderId()).isEqualTo(orderId);
        assertThat(received.userSubject()).isEqualTo("trader-1");
        assertThat(received.quantityScaled()).isEqualTo(command.quantityScaled());
        assertThat(received.limitPriceScaled()).isEqualTo(command.limitPriceScaled());
    }

    @Test
    void nonSbePayloadIsIgnored() {
        DisruptorOrderPipeline mockPipeline = mock(DisruptorOrderPipeline.class);
        AeronOrderCommandSubscriber subscriber = new AeronOrderCommandSubscriber(mockPipeline);

        byte[] junk = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        UnsafeBuffer buffer = new UnsafeBuffer(junk);

        subscriber.onFragment(buffer, 0, junk.length);

        org.mockito.Mockito.verifyNoInteractions(mockPipeline);
    }
}
