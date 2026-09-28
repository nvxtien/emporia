# Trading contracts

`trading-contracts` is a shared Java library, not a deployable service. It owns
the versioned command, result, domain-event, order-view, and listing-snapshot
types exchanged by the Emporia services, plus the SBE codecs used by Aeron
intake.

The Java package remains `com.emporia.events` for wire compatibility with the
existing binary and persisted event contracts.
