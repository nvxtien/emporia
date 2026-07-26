package com.emporia.marketdata;

import com.emporia.marketdata.MarketDataService.DepthLevel;
import com.emporia.marketdata.MarketDataService.Quote;
import com.emporia.marketdata.grpc.model.ClobLine;
import com.emporia.marketdata.grpc.model.ClobQuote;
import com.emporia.marketdata.grpc.model.Decimal64;

import java.math.BigDecimal;

final class GrpcQuoteConverter {
    private GrpcQuoteConverter() {
    }

    static ClobQuote convert(Quote quote) {
        ClobQuote.Builder result = ClobQuote.newBuilder()
                .setListingId(Math.toIntExact(quote.listingId()))
                .setStreamInterrupted(quote.streamInterrupted())
                .setStreamStatusMsg(quote.streamStatusMessage())
                .setLastPrice(decimal(quote.lastPrice()))
                .setLastQuantity(decimal(quote.lastQuantity()))
                .setTradedVolume(decimal(quote.tradedVolume()));
        quote.bids().stream().map(GrpcQuoteConverter::line).forEach(result::addBids);
        quote.offers().stream().map(GrpcQuoteConverter::line).forEach(result::addOffers);
        return result.build();
    }

    private static ClobLine line(DepthLevel level) {
        return ClobLine.newBuilder()
                .setPrice(decimal(level.price()))
                .setSize(decimal(level.size()))
                .setEntryId(level.entryId())
                .setListingId(Math.toIntExact(level.listingId()))
                .build();
    }

    private static Decimal64 decimal(BigDecimal value) {
        if (value == null) {
            return Decimal64.getDefaultInstance();
        }
        BigDecimal normalized = value.stripTrailingZeros();
        return Decimal64.newBuilder()
                .setMantissa(normalized.unscaledValue().longValueExact())
                .setExponent(-normalized.scale())
                .build();
    }
}
