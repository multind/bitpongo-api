package com.multind.bitpongo.exchange;

import com.multind.bitpongo.common.api.BusinessException;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component("binanceFuturesGateway")
public class BinanceFuturesGateway implements FuturesGateway {
    private final BinanceFuturesClient client;

    public BinanceFuturesGateway(BinanceFuturesClient client) {
        this.client = client;
    }

    @Override
    public Balance balance(ExchangeCredentials credentials) {
        try {
            return client.balance(credentials);
        } catch (BinanceClientException exception) {
            throw map(exception, false);
        }
    }

    @Override
    public Rules rules(String symbol) {
        try {
            return client.rules(normalizeSymbol(symbol));
        } catch (BinanceClientException exception) {
            throw map(exception, false);
        }
    }

    @Override
    public BigDecimal markPrice(String symbol) {
        try {
            return client.markPrice(normalizeSymbol(symbol));
        } catch (BinanceClientException exception) {
            throw map(exception, false);
        }
    }

    @Override
    public void setLeverage(ExchangeCredentials credentials, String symbol, int leverage) {
        try {
            client.setLeverage(credentials, normalizeSymbol(symbol), leverage);
        } catch (BinanceClientException exception) {
            throw map(exception, false);
        }
    }

    @Override
    public boolean oneWayMode(ExchangeCredentials credentials) {
        try {
            return client.oneWayMode(credentials);
        } catch (BinanceClientException exception) {
            throw map(exception, false);
        }
    }

    @Override
    public Order marketOrder(
            ExchangeCredentials credentials,
            String symbol,
            String side,
            BigDecimal quantity,
            String clientOrderId,
            boolean reduceOnly) {
        try {
            return client.marketOrder(credentials, normalizeSymbol(symbol), side, quantity, clientOrderId, reduceOnly);
        } catch (BinanceClientException exception) {
            throw map(exception, true);
        }
    }

    @Override
    public Optional<Order> findOrder(ExchangeCredentials credentials, String symbol, String clientOrderId) {
        try {
            return client.findOrder(credentials, normalizeSymbol(symbol), clientOrderId);
        } catch (BinanceClientException exception) {
            throw map(exception, false);
        }
    }

    @Override
    public Optional<Position> position(ExchangeCredentials credentials, String symbol) {
        try {
            return client.position(credentials, normalizeSymbol(symbol));
        } catch (BinanceClientException exception) {
            throw map(exception, false);
        }
    }

    private static RuntimeException map(BinanceClientException exception, boolean orderSubmission) {
        if (orderSubmission && (exception.timeout()
                || exception.httpStatus() >= 500 || exception.httpStatus() <= 0
                || exception.errorCode() == -1006 || exception.errorCode() == -1007)) {
            return new AmbiguousOrderException("合约下单结果不明确，请按客户端订单号查询", exception);
        }
        if (exception.errorCode() == -2015 || exception.httpStatus() == 401) {
            return new BusinessException(400, "合约 API 密钥认证失败，请检查密钥和合约权限");
        }
        if (exception.errorCode() == -4028) {
            return new BusinessException(400, "杠杆倍数超出该交易对允许范围");
        }
        if (exception.httpStatus() == 429 || exception.httpStatus() >= 500 || exception.timeout()) {
            return new RetryableExchangeException("Binance 合约接口暂时不可用，请稍后重试", exception);
        }
        return new BusinessException(502, "Binance 合约请求失败");
    }

    private static String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) throw new BusinessException(400, "交易对不能为空");
        return symbol.replace("/", "").trim().toUpperCase(Locale.ROOT);
    }
}