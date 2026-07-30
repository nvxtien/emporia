package com.emporia.staticdata;

import com.emporia.events.TradingEvents.ListingSnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "instrument_listing")
class InstrumentListing {
    @Id private Long id;
    @Column(nullable = false) private int version;
    @Column(nullable = false, length = 24) private String symbol;
    @Column(nullable = false, length = 200) private String name;
    @Column(name = "market_symbol", nullable = false, length = 24) private String marketSymbol;
    @Column(name = "exchange_mic", nullable = false, length = 12) private String exchangeMic;
    @Column(name = "exchange_name", nullable = false, length = 120) private String exchangeName;
    @Column(name = "country_code", nullable = false, length = 2) private String countryCode;
    @Column(nullable = false, length = 3) private String currency;
    @Column(nullable = false) private boolean enabled;
    @Column(name = "tick_size", nullable = false, precision = 19, scale = 6) private BigDecimal tickSize;
    @Column(name = "size_increment", nullable = false, precision = 19, scale = 6) private BigDecimal sizeIncrement;
    @Column(name = "reference_price", nullable = false, precision = 19, scale = 6) private BigDecimal referencePrice;
    @Column(name = "previous_close", nullable = false, precision = 19, scale = 6) private BigDecimal previousClose;

    protected InstrumentListing() {
    }

    InstrumentListing(ListingSnapshot snapshot, boolean enabled) {
        this.id = snapshot.id();
        this.version = snapshot.version();
        this.symbol = snapshot.symbol();
        this.name = snapshot.name();
        this.marketSymbol = snapshot.marketSymbol();
        this.exchangeMic = snapshot.exchangeMic();
        this.exchangeName = snapshot.exchangeName();
        this.countryCode = snapshot.countryCode();
        this.currency = snapshot.currency();
        this.tickSize = snapshot.tickSize();
        this.sizeIncrement = snapshot.sizeIncrement();
        this.referencePrice = snapshot.referencePrice();
        this.previousClose = snapshot.previousClose();
        this.enabled = enabled;
    }

    boolean isEnabled() { return enabled; }

    ListingSnapshot snapshot() {
        return new ListingSnapshot(id, version, symbol, name, marketSymbol, exchangeMic, exchangeName,
                countryCode, currency, tickSize, sizeIncrement, referencePrice, previousClose);
    }
}
