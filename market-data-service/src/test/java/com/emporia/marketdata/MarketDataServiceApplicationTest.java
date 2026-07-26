package com.emporia.marketdata;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "emporia.market-data.provider=simulated",
                "emporia.market-data.grpc.port=0"
        }
)
class MarketDataServiceApplicationTest {

    @Test
    void contextLoadsWithTheDefaultProvider() {
    }
}
