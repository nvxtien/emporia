# Static data service

The static-data service owns Emporia's instrument listings and exchange
metadata on port `8081`. Other services access it through authenticated HTTP;
they do not connect to its PostgreSQL schema.

## Alpaca asset source

`AlpacaReferenceDataImporter` reads active, tradable US equities from
`${APCA_API_ENDPOINT}/assets?status=active&asset_class=us_equity`. It creates a
primary ISO-MIC listing and an `XOSR` composite listing for every asset. Alpaca
asset IDs produce deterministic Emporia listing IDs, so restarting the importer
updates existing rows rather than duplicating them.

The Alpaca importer is opt-in:

```bash
ALPACA_REFERENCE_DATA_IMPORT_ENABLED=true \
APCA_API_KEY_ID=your-alpaca-key-id \
APCA_API_SECRET_KEY=your-alpaca-secret-key \
mvn -f emporia/static-data-service/pom.xml spring-boot:run
```

Credentials are read from the process environment and used only as request
headers. They are not written to PostgreSQL, configuration files, or logs.
`APCA_API_ENDPOINT` defaults to `https://paper-api.alpaca.markets/v2`.

Primary exchange mappings include Nasdaq (`XNAS`), NYSE (`XNYS`), NYSE Arca
(`ARCX`), NYSE American (`XASE`), and Cboe BZX (`BATS`). Fractionable assets
use a fractional size increment; all assets receive a configurable seed
reference price through `ALPACA_DEFAULT_REFERENCE_PRICE`.

## Verify

```bash
mvn -f emporia/pom.xml -pl static-data-service -am test
```

The tests cover asset filtering, exchange mapping, and deterministic ID
generation.
