# Trading contracts

`trading-contracts` is a shared Java library, not a deployable service. It owns
the versioned command, result, domain-event, order-view, and listing-snapshot
types exchanged by the Emporia services, together with their Kafka topic names.

The Java package remains `com.emporia.events` for wire compatibility. Spring
Kafka JSON type headers can contain fully qualified Java class names, so changing
that package would prevent consumers from deserializing messages published with
the existing contract.
