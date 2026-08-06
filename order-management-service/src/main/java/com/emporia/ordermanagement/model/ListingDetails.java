package com.emporia.ordermanagement.model;

import com.emporia.events.TradingEvents.ListingSnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;

import java.math.BigDecimal;

@Embeddable
@Getter
public class ListingDetails {
    @Column(name = "listing_id", nullable = false) private long id;
    @Column(name = "listing_version", nullable = false) private int version;
    @Column(name = "listing_symbol", nullable = false, length = 24) private String symbol;
    @Column(name = "listing_name", nullable = false, length = 200) private String name;
    @Column(name = "market_symbol", nullable = false, length = 24) private String marketSymbol;
    @Column(name = "exchange_mic", nullable = false, length = 12) private String exchangeMic;
    @Column(name = "exchange_name", nullable = false, length = 120) private String exchangeName;
    @Column(name = "country_code", nullable = false, length = 2) private String countryCode;
    @Column(nullable = false, length = 3) private String currency;
    @Column(name = "tick_size", nullable = false, precision = 19, scale = 6) private BigDecimal tickSize;
    @Column(name = "size_increment", nullable = false, precision = 19, scale = 6) private BigDecimal sizeIncrement;
    @Column(name = "reference_price", nullable = false, precision = 19, scale = 6) private BigDecimal referencePrice;
    @Column(name = "previous_close", nullable = false, precision = 19, scale = 6) private BigDecimal previousClose;

    protected ListingDetails() {
    }

    ListingDetails(ListingSnapshot listing) {
        this.id = listing.id(); this.version = listing.version(); this.symbol = listing.symbol(); this.name = listing.name();
        this.marketSymbol = listing.marketSymbol(); this.exchangeMic = listing.exchangeMic(); this.exchangeName = listing.exchangeName();
        this.countryCode = listing.countryCode(); this.currency = listing.currency(); this.tickSize = listing.tickSize();
        this.sizeIncrement = listing.sizeIncrement(); this.referencePrice = listing.referencePrice(); this.previousClose = listing.previousClose();
        this.tickSizeScaled = listing.tickSizeScaled();
        this.sizeIncrementScaled = listing.sizeIncrementScaled();
    }

    private transient long tickSizeScaled;
    private transient long sizeIncrementScaled;

    public long getTickSizeScaled() {
        if (tickSizeScaled == 0L && tickSize != null) {
            tickSizeScaled = com.emporia.events.math.FixedPointMath.toScaledLong(tickSize);
        }
        return tickSizeScaled;
    }

    public long getSizeIncrementScaled() {
        if (sizeIncrementScaled == 0L && sizeIncrement != null) {
            sizeIncrementScaled = com.emporia.events.math.FixedPointMath.toScaledLong(sizeIncrement);
        }
        return sizeIncrementScaled;
    }

    ListingSnapshot snapshot() {
        return new ListingSnapshot(id, version, symbol, name, marketSymbol, exchangeMic, exchangeName, countryCode,
                currency, tickSize, sizeIncrement, referencePrice, previousClose, getTickSizeScaled(), getSizeIncrementScaled());
    }
}
