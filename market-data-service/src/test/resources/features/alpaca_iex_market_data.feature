Feature: Alpaca IEX market data
  Emporia should translate Alpaca IEX events into a stable quote model for the workspace.

  Scenario: Display the latest trade and two-sided top of book
    Given an "AAPL" listing with previous close "198.00"
    When Alpaca IEX publishes these market-data messages
      """
      [
        {
          "T": "q",
          "S": "AAPL",
          "bx": "V",
          "bp": 199.10,
          "bs": 2,
          "ax": "V",
          "ap": 199.20,
          "as": 3,
          "t": "2026-07-23T14:30:00.100Z"
        },
        {
          "T": "t",
          "S": "AAPL",
          "x": "V",
          "p": 199.15,
          "s": 25,
          "t": "2026-07-23T14:30:00.200Z"
        }
      ]
      """
    Then requesting the Emporia quote returns source "ALPACA_IEX"
    And the last price is "199.15" with quantity "25"
    And the best bid is "199.10" for "200" shares on "IEXG"
    And the best offer is "199.20" for "300" shares on "IEXG"

  Scenario: Preserve an ask-only IEX book
    Given an "AAPL" listing with previous close "198.00"
    When Alpaca IEX publishes these market-data messages
      """
      [
        {
          "T": "q",
          "S": "AAPL",
          "bx": " ",
          "bp": 0,
          "bs": 0,
          "ax": "V",
          "ap": 201.20,
          "as": 4,
          "t": "2026-07-23T14:31:00.100Z"
        }
      ]
      """
    Then requesting the Emporia quote returns source "ALPACA_IEX"
    And the bid book is empty
    And the best offer is "201.20" for "400" shares on "IEXG"
