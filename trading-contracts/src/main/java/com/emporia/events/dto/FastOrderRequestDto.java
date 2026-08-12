package com.emporia.events.dto;

import com.dslplatform.json.CompiledJson;
import com.dslplatform.json.JsonAttribute;

/**
 * Ultra-Low Latency Order Request DTO compiled for zero-reflection DSL-JSON byte stream parsing.
 * Uses 64-bit primitive long fixed-point scale factor (1,000,000L) for price and quantity.
 */
@CompiledJson
public record FastOrderRequestDto(
        @JsonAttribute(name = "orderId") String orderId,
        @JsonAttribute(name = "clientId") long clientId,
        @JsonAttribute(name = "symbol") String symbol,
        @JsonAttribute(name = "side") String side,
        @JsonAttribute(name = "price") long priceScaled,
        @JsonAttribute(name = "quantity") long quantityScaled
) {
    public boolean isValid() {
        return orderId != null && !orderId.isBlank()
                && clientId > 0
                && symbol != null && !symbol.isBlank()
                && side != null && (side.equalsIgnoreCase("BUY") || side.equalsIgnoreCase("SELL"))
                && priceScaled > 0
                && quantityScaled > 0;
    }
}
