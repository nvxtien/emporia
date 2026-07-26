# Order command service

The order command service is Emporia's authenticated HTTP command boundary for order
mutations. It runs on port `8085` and exposes:

```text
POST /orders
PUT  /orders/{orderId}
POST /orders/{orderId}/cancel
POST /orders/cancel-all
```

The gateway publishes these as `/api/orders/**`. The public paths did not
change when cancel-all moved from the removed `order-monitor` service.

## Command flow

For create requests, the service loads an immutable listing snapshot from
`static-data-service`. It then creates a versioned `OrderCommand`, publishes it
to `emporia.order.commands.v1`, and waits up to eight seconds for the correlated
result on `emporia.order.results.v1`.

Create, modify, and single-cancel commands use the order ID as their Kafka key.
Cancel-all has no order ID, so it uses the authenticated user's subject. This
keeps all bulk cancellation work for one user ordered on the same partition.

`order-management-service` owns validation, state transitions, persistence,
history, and idempotency. The service does not store orders in PostgreSQL.

## Configuration

| Environment variable | Default |
|---|---|
| `SERVER_PORT` | `8085` |
| `EMPORIA_STATIC_DATA_URL` | `http://localhost:8081` |
| `KAFKA_BROKERS` | `localhost:9092` |
| `KAFKA_ORDER_COMMANDS_TOPIC` | `emporia.order.commands.v1` |
| `KAFKA_ORDER_RESULTS_TOPIC` | `emporia.order.results.v1` |
| `KAFKA_COMMAND_TIMEOUT` | `8s` |

## Run and test

```bash
cd emporia/order-command-service
mvn spring-boot:run
```

From the repository root:

```bash
mvn -f emporia/pom.xml -pl order-command-service -am test
```

`OrderCommandControllerTest` verifies that cancel-all produces a user-scoped
`CANCEL_ALL` command. The gateway integration suite separately verifies that
`POST /api/orders/cancel-all` is routed to this service.
