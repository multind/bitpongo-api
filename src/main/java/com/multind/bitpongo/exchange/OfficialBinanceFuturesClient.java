package com.multind.bitpongo.exchange;

import com.binance.connector.client.common.ApiException;
import com.binance.connector.client.common.configuration.ClientConfiguration;
import com.binance.connector.client.common.configuration.SignatureConfiguration;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.api.DerivativesTradingUsdsFuturesRestApi;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.ChangeInitialLeverageRequest;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.ExchangeInformationResponseSymbolsInner;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.FuturesAccountBalanceV2ResponseInner;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.MarkPriceResponse1;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.MarkPriceResponse2;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.NewOrderRequest;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.NewOrderResponse;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.OrderType;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.PositionInformationV2ResponseInner;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.QueryOrderResponse;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.ReduceOnly;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.Side;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multind.bitpongo.common.api.BusinessException;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class OfficialBinanceFuturesClient implements BinanceFuturesClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final BinanceFuturesEnvironment environment;

    public OfficialBinanceFuturesClient(BinanceFuturesEnvironment environment) {
        this.environment = environment;
    }

    @Override
    public FuturesGateway.Balance balance(ExchangeCredentials credentials) {
        try {
            var balances = authenticatedApi(credentials).futuresAccountBalanceV2(null).getData();
            if (balances == null) return zero();
            for (FuturesAccountBalanceV2ResponseInner balance : balances) {
                if ("USDT".equalsIgnoreCase(balance.getAsset())) {
                    return new FuturesGateway.Balance(decimal(balance.getAvailableBalance()), decimal(balance.getBalance()));
                }
            }
            return zero();
        } catch (ApiException exception) {
            throw translate(exception);
        }
    }

    @Override
    public FuturesGateway.Rules rules(String symbol) {
        try {
            var response = publicApi().exchangeInformation().getData();
            var item = Optional.ofNullable(response.getSymbols()).orElseGet(List::of).stream()
                    .filter(candidate -> symbol.equalsIgnoreCase(candidate.getSymbol()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(404, "合约交易对不存在: " + symbol));
            BigDecimal minNotional = BigDecimal.ZERO;
            BigDecimal stepSize = BigDecimal.ZERO;
            BigDecimal minQty = BigDecimal.ZERO;
            BigDecimal maxQty = BigDecimal.ZERO;
            for (var filter : Optional.ofNullable(item.getFilters()).orElseGet(List::of)) {
                String type = filter.getFilterType();
                if ("MIN_NOTIONAL".equalsIgnoreCase(type)) {
                    minNotional = decimal(filter.getNotional());
                } else if ("MARKET_LOT_SIZE".equalsIgnoreCase(type)) {
                    stepSize = decimal(filter.getStepSize());
                    minQty = decimal(filter.getMinQty());
                    maxQty = decimal(filter.getMaxQty());
                }
            }
            if (stepSize.signum() <= 0) throw new BusinessException(502, "合约未返回数量规则");
            return new FuturesGateway.Rules(minNotional, stepSize, minQty, maxQty,
                    intValue(item.getPricePrecision()), intValue(item.getQuantityPrecision()));
        } catch (ApiException exception) {
            throw translate(exception);
        }
    }

    @Override
    public BigDecimal markPrice(String symbol) {
        try {
            var response = publicApi().markPrice(symbol).getData();
            Object actual = response.getActualInstance();
            if (actual instanceof MarkPriceResponse1 single) return decimal(single.getMarkPrice());
            if (actual instanceof MarkPriceResponse2 list && !list.isEmpty()) {
                return decimal(list.getFirst().getMarkPrice());
            }
            throw new BusinessException(502, "合约标记价格格式无效");
        } catch (ApiException exception) {
            throw translate(exception);
        }
    }

    @Override
    public void setLeverage(ExchangeCredentials credentials, String symbol, int leverage) {
        try {
            authenticatedApi(credentials).changeInitialLeverage(
                    new ChangeInitialLeverageRequest().symbol(symbol).leverage((long) leverage));
        } catch (ApiException exception) {
            throw translate(exception);
        }
    }

    @Override
    public boolean oneWayMode(ExchangeCredentials credentials) {
        try {
            var response = authenticatedApi(credentials).getCurrentPositionMode(null).getData();
            return !Boolean.TRUE.equals(response.getDualSidePosition());
        } catch (ApiException exception) {
            throw translate(exception);
        }
    }

    @Override
    public FuturesGateway.Order marketOrder(
            ExchangeCredentials credentials,
            String symbol,
            String side,
            BigDecimal quantity,
            String clientOrderId,
            boolean reduceOnly) {
        try {
            NewOrderRequest request = new NewOrderRequest()
                    .symbol(symbol)
                    .side(Side.fromValue(side))
                    .positionSide("BOTH")
                    .type(OrderType.MARKET)
                    .quantity(quantity.doubleValue())
                    .newClientOrderId(clientOrderId)
                    .reduceOnly(reduceOnly ? ReduceOnly.TRUE : ReduceOnly.FALSE);
            NewOrderResponse response = authenticatedApi(credentials).newOrder(request).getData();
            return new FuturesGateway.Order(response.getClientOrderId(), response.getOrderId(),
                    response.getStatus(), decimal(response.getExecutedQty()), BigDecimal.ZERO);
        } catch (ApiException exception) {
            throw translate(exception);
        }
    }

    @Override
    public Optional<FuturesGateway.Order> findOrder(
            ExchangeCredentials credentials, String symbol, String clientOrderId) {
        try {
            QueryOrderResponse response = authenticatedApi(credentials)
                    .queryOrder(symbol, null, clientOrderId, null).getData();
            return Optional.of(new FuturesGateway.Order(response.getClientOrderId(), response.getOrderId(),
                    response.getStatus(), decimal(response.getExecutedQty()), decimal(response.getAvgPrice()),
                    response.getCumQuote() == null ? null : decimal(response.getCumQuote())));
        } catch (ApiException exception) {
            BinanceClientException translated = translate(exception);
            if (translated.errorCode() == -2013 || translated.httpStatus() == 404) return Optional.empty();
            throw translated;
        }
    }

    @Override
    public Optional<FuturesGateway.Position> position(ExchangeCredentials credentials, String symbol) {
        try {
            var positions = authenticatedApi(credentials).positionInformationV2(symbol, null).getData();
            if (positions == null) return Optional.empty();
            for (PositionInformationV2ResponseInner position : positions) {
                if (decimal(position.getPositionAmt()).signum() != 0) {
                    return Optional.of(new FuturesGateway.Position(
                            position.getSymbol(),
                            decimal(position.getPositionAmt()),
                            decimal(position.getEntryPrice()),
                            decimal(position.getMarkPrice()),
                            decimal(position.getUnRealizedProfit())));
                }
            }
            return Optional.empty();
        } catch (ApiException exception) {
            throw translate(exception);
        }
    }

    private DerivativesTradingUsdsFuturesRestApi publicApi() {
        return new DerivativesTradingUsdsFuturesRestApi(configuration(null));
    }

    private DerivativesTradingUsdsFuturesRestApi authenticatedApi(ExchangeCredentials credentials) {
        return new DerivativesTradingUsdsFuturesRestApi(configuration(credentials));
    }

    private ClientConfiguration configuration(ExchangeCredentials credentials) {
        ClientConfiguration configuration = new ClientConfiguration();
        configuration.setUrl(environment.effectiveRestBaseUrl());
        configuration.setRetries(0);
        if (credentials != null) {
            SignatureConfiguration signature = new SignatureConfiguration();
            signature.setApiKey(credentials.accessKey());
            signature.setSecretKey(credentials.secretKey());
            configuration.setSignatureConfiguration(signature);
        }
        return configuration;
    }

    private static BinanceClientException translate(ApiException exception) {
        int errorCode = 0;
        try {
            JsonNode body = JSON.readTree(exception.getResponseBody());
            errorCode = body.path("code").asInt(0);
        } catch (Exception ignored) {
            // Binance may return an empty or non-JSON body during network and gateway failures.
        }
        boolean timeout = exception.getCause() instanceof SocketTimeoutException
                || (exception.getMessage() != null
                && exception.getMessage().toLowerCase().contains("timeout"));
        return new BinanceClientException(exception.getCode(), errorCode, exception.getMessage(), timeout);
    }

    private static int intValue(Long value) {
        return value == null ? 0 : value.intValue();
    }

    private static FuturesGateway.Balance zero() {
        return new FuturesGateway.Balance(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static BigDecimal decimal(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }
}
