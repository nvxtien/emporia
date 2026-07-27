package com.emporia.portfolio;

import java.util.Map;

record ValidatedPortfolioSnapshot(
        String exchangeId,
        long deliveryId,
        long clientId,
        Map<Integer, Long> availableBalances) {
}
