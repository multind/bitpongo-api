package com.multind.bitpongo.exchange;

import java.math.BigDecimal;
import java.util.Optional;

/** Minimal USDT-M futures gateway used by the F&amp;G execution engine. */
public interface FuturesGateway {
    Balance balance(ExchangeCredentials credentials);

    Rules rules(String symbol);

    BigDecimal markPrice(String symbol);

    void setLeverage(ExchangeCredentials credentials, String symbol, int leverage);

    /** True when the account is in one-way position mode. */
    boolean oneWayMode(ExchangeCredentials credentials);

    Order marketOrder(
            ExchangeCredentials credentials,
            String symbol,
            String side,
            BigDecimal quantity,
            String clientOrderId,
            boolean reduceOnly);

    Optional<Order> findOrder(ExchangeCredentials credentials, String symbol, String clientOrderId);

    Optional<Position> position(ExchangeCredentials credentials, String symbol);

    record Balance(BigDecimal available, BigDecimal total) {}

    record Rules(
            BigDecimal minNotional,
            BigDecimal stepSize,
            BigDecimal minQty,
            BigDecimal maxQty,
            int pricePrecision,
            int quantityPrecision) {}

    record Order(String clientOrderId, long orderId, String status, BigDecimal executedQty, BigDecimal avgPrice) {}

    record Position(String symbol, BigDecimal amount, BigDecimal entryPrice, BigDecimal markPrice,
            BigDecimal unrealizedProfit) {}
}