package com.multind.bitpongo.exchange;

import com.multind.bitpongo.common.api.BusinessException;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component("binanceExchangeGateway")
public class BinanceExchangeGateway implements ExchangeGateway {
    private final BinanceSpotClient client;

    public BinanceExchangeGateway(BinanceSpotClient client) {
        this.client = client;
    }

    @Override
    public AccountBalance verifyCredentials(ExchangeCredentials credentials) {
        try {
            return client.account(credentials);
        } catch (BinanceClientException exception) {
            throw map(exception, false);
        }
    }

    @Override
    public MarketRules getMarketRules(String symbol) {
        try {
            return client.marketRules(normalizeSymbol(symbol));
        } catch (BinanceClientException exception) {
            throw map(exception, false);
        }
    }

    @Override
    public OrderResult marketBuy(
            ExchangeCredentials credentials, String symbol, BigDecimal quantity, String clientOrderId) {
        try {
            return client.marketBuy(credentials, normalizeSymbol(symbol), quantity, clientOrderId);
        } catch (BinanceClientException exception) {
            throw map(exception, true);
        }
    }

    @Override
    public Optional<OrderResult> findOrder(
            ExchangeCredentials credentials, String symbol, String clientOrderId) {
        try {
            return client.findOrder(credentials, normalizeSymbol(symbol), clientOrderId);
        } catch (BinanceClientException exception) {
            throw map(exception, false);
        }
    }

    private static RuntimeException map(BinanceClientException exception, boolean orderSubmission) {
        if (orderSubmission && (exception.timeout()
                || exception.httpStatus() >= 500 || exception.httpStatus() <= 0
                || exception.errorCode() == -1006 || exception.errorCode() == -1007)) {
            return new AmbiguousOrderException("下单结果不明确，请按客户端订单号查询", exception);
        }
        if (exception.errorCode() == -2015 || exception.httpStatus() == 401) {
            return new BusinessException(400, "API密钥认证失败，请检查密钥是否正确");
        }
        if (exception.httpStatus() == 429 || exception.httpStatus() >= 500 || exception.timeout()) {
            return new RetryableExchangeException("Binance 暂时不可用，请稍后重试", exception);
        }
        return new BusinessException(502, "Binance 请求失败");
    }

    private static String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new BusinessException(400, "交易对不能为空");
        }
        return symbol.replace("/", "").trim().toUpperCase(Locale.ROOT);
    }
}
