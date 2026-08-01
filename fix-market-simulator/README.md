# fix-market-simulator

Simulates a central limit order book.  Exposes a FIX api for order entry and FIX MD api over gRpc for market data.  In addition a swagger api is provided to submit orders and view order book state.  The simulator can be configured on a per instrument basis to simulate a live trading book against which to trade and act as a source of market data.

Runs as part of the emporia Maven reactor (`mvn -f pom.xml -pl fix-market-simulator -am ...` from the repo root) but is a standalone app (Guice/Jetty/Jersey/QuickFIX-J, not Spring Boot) with its own groupId/version, not parented to `emporia-services`. Its Maven artifactId and the module directory match (`fix-market-simulator`), but its Java packages are untouched (`com.ettech.fixmarketsimulator.*`) -- that's the app's own internal namespace from its original upstream identity. Its FIX-protocol and gRPC contract classes come from the [`fix-simulator-contracts`](../fix-simulator-contracts) module, which `market-data-service` also depends on.

Default ports: `FIX_SERVER_PORT` 9876, `MARKET_DATA_SERVER_PORT` 50051 (gRPC), `REST_API_PORT` 8501 (Jetty/Jersey + swagger UI).