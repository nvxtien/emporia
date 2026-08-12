package com.emporia.events.dto;

import com.dslplatform.json.CompiledJson;
import com.dslplatform.json.JsonAttribute;

@CompiledJson
public record FastOrderRequestDto(
        @JsonAttribute(name = "orderId") String orderId,
        @JsonAttribute(name = "clientId") long clientId,
        @JsonAttribute(name = "symbol") String symbol,
        @JsonAttribute(name = "side") String side,
        @JsonAttribute(name = "price") long priceScaled,
        @JsonAttribute(name = "quantity") long quantityScaled
) {}
