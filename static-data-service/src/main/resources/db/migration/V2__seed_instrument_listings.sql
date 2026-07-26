INSERT INTO instrument_listing
    (id, symbol, name, market_symbol, exchange_mic, exchange_name, country_code, currency,
     tick_size, size_increment, reference_price, previous_close)
VALUES
    (1, 'AAPL', 'Apple Inc.', 'AAPL', 'XNAS', 'Nasdaq', 'US', 'USD', 0.01, 1, 236.45, 233.92),
    (2, 'NVDA', 'NVIDIA Corporation', 'NVDA', 'XNAS', 'Nasdaq', 'US', 'USD', 0.01, 1, 141.97, 138.77),
    (3, 'MSFT', 'Microsoft Corporation', 'MSFT', 'XNAS', 'Nasdaq', 'US', 'USD', 0.01, 1, 418.79, 416.96),
    (4, 'TSLA', 'Tesla, Inc.', 'TSLA', 'XNAS', 'Nasdaq', 'US', 'USD', 0.01, 1, 328.11, 332.33),
    (5, 'AMZN', 'Amazon.com, Inc.', 'AMZN', 'XNAS', 'Nasdaq', 'US', 'USD', 0.01, 1, 227.16, 224.21),
    (6, 'META', 'Meta Platforms, Inc.', 'META', 'XNAS', 'Nasdaq', 'US', 'USD', 0.01, 1, 612.77, 607.91),
    (7, 'GOOGL', 'Alphabet Inc. Class A', 'GOOGL', 'XNAS', 'Nasdaq', 'US', 'USD', 0.01, 1, 193.18, 191.33),
    (8, 'JPM', 'JPMorgan Chase & Co.', 'JPM', 'XNYS', 'New York Stock Exchange', 'US', 'USD', 0.01, 1, 267.14, 265.80),
    (9, 'V', 'Visa Inc.', 'V', 'XNYS', 'New York Stock Exchange', 'US', 'USD', 0.01, 1, 348.22, 346.42),
    (10, 'BRK.B', 'Berkshire Hathaway Inc. Class B', 'BRK.B', 'XNYS', 'New York Stock Exchange', 'US', 'USD', 0.01, 1, 478.64, 476.05),
    (11, 'IBM', 'International Business Machines Corporation', 'IBM', 'XNYS', 'New York Stock Exchange', 'US', 'USD', 0.01, 1, 259.28, 257.70),
    (12, 'ORCL', 'Oracle Corporation', 'ORCL', 'XNYS', 'New York Stock Exchange', 'US', 'USD', 0.01, 1, 173.52, 172.18);
