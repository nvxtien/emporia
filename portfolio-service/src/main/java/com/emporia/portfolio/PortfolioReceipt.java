package com.emporia.portfolio;

import java.util.Arrays;

record PortfolioReceipt(
        String payloadSha256,
        byte[] payload) {

    PortfolioReceipt {
        payload = Arrays.copyOf(payload, payload.length);
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
