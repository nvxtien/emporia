package com.emporia.ordermanagement.dto;

import com.emporia.events.TradingEvents;

import java.util.List;

public record ProcessingOutcome(TradingEvents.OrderCommandResult result, List<TradingEvents.OrderDomainEvent> events) { }