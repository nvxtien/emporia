package com.emporia.portfolio;

import java.util.Map;

record ValidatedPortfolioSnapshot(
        String exchangeId,
        long deliveryId,
        long clientId,
        boolean settled,
        Map<Integer, Long> availableBalances) {
}
