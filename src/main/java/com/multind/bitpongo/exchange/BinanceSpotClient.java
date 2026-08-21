package com.multind.bitpongo.exchange;

import java.math.BigDecimal;
import java.util.Optional;

public interface BinanceSpotClient {
    AccountBalance account(ExchangeCredentials credentials);
    MarketRules marketRules(String symbol);
    BigDecimal latestPrice(String symbol);
    OrderResult marketBuy(
            ExchangeCredentials credentials, String symbol, BigDecimal quantity, String clientOrderId);
    Optional<OrderResult> findOrder(
            ExchangeCredentials credentials, String symbol, String clientOrderId);
}
