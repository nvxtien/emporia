INSERT INTO instrument_listing
    (id, version, symbol, name, market_symbol, exchange_mic, exchange_name, country_code, currency,
     enabled, tick_size, size_increment, reference_price, previous_close)
SELECT
    id + 1000,
    version,
    symbol,
    name,
    market_symbol,
    'XOSR',
    'Smart Order Router',
    country_code,
    currency,
    enabled,
    tick_size,
    size_increment,
    reference_price,
    previous_close
FROM instrument_listing source
WHERE source.exchange_mic <> 'XOSR'
  AND source.id BETWEEN 1 AND 12
  AND NOT EXISTS (
      SELECT 1
      FROM instrument_listing target
      WHERE target.id = source.id + 1000
         OR (target.market_symbol = source.market_symbol AND target.exchange_mic = 'XOSR')
  );
