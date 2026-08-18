package com.multind.bitpongo.exchange;

import com.binance.connector.client.common.ApiException;
import com.binance.connector.client.common.configuration.ClientConfiguration;
import com.binance.connector.client.common.configuration.SignatureConfiguration;
import com.binance.connector.client.spot.rest.api.SpotRestApi;
import com.binance.connector.client.spot.rest.model.ExchangeInfoResponseSymbolsInner;
import com.binance.connector.client.spot.rest.model.GetOrderResponse;
import com.binance.connector.client.spot.rest.model.LotSizeFilter;
import com.binance.connector.client.spot.rest.model.MinNotionalFilter;
import com.binance.connector.client.spot.rest.model.MyTradesResponseInner;
import com.binance.connector.client.spot.rest.model.NewOrderRequest;
import com.binance.connector.client.spot.rest.model.NewOrderRespType;
import com.binance.connector.client.spot.rest.model.NewOrderResponse;
import com.binance.connector.client.spot.rest.model.NotionalFilter;
import com.binance.connector.client.spot.rest.model.OrderType;
import com.binance.connector.client.spot.rest.model.Side;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multind.bitpongo.common.api.BusinessException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class OfficialBinanceSpotClient implements BinanceSpotClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final BinanceEnvironment environment;

    public OfficialBinanceSpotClient(BinanceEnvironment environment) {
        this.environment = environment;
    }

    @Override
    public AccountBalance account(ExchangeCredentials credentials) {
        try {
            var balances = authenticatedApi(credentials).getAccount(true, null).getData().getBalances();
            return balances == null ? zeroUsdt() : balances.stream()
                    .filter(balance -> "USDT".equalsIgnoreCase(balance.getAsset()))
                    .findFirst()
                    .map(balance -> new AccountBalance(
                            "USDT", decimal(balance.getFree()), decimal(balance.getLocked())))
                    .orElseGet(OfficialBinanceSpotClient::zeroUsdt);
        } catch (ApiException exception) {
            throw translate(exception);
        }
    }

    @Override
    public MarketRules marketRules(String symbol) {
        try {
            var response = publicApi().exchangeInfo(symbol, null, null, null, null).getData();
            ExchangeInfoResponseSymbolsInner item = Optional.ofNullable(response.getSymbols())
                    .orElseGet(List::of).stream().findFirst()
                    .orElseThrow(() -> new BusinessException(404, "交易对不存在: " + symbol));
            LotSizeFilter lot = null;
            BigDecimal minimumNotional = BigDecimal.ZERO;
            for (var filter : Optional.ofNullable(item.getFilters()).orElseGet(List::of)) {
                Object actual = filter.getActualInstance();
                if (actual instanceof LotSizeFilter value) {
                    lot = value;
                } else if (actual instanceof MinNotionalFilter value) {
                    minimumNotional = decimal(value.getMinNotional());
                } else if (actual instanceof NotionalFilter value) {
                    minimumNotional = decimal(value.getMinNotional());
                }
            }
            if (lot == null) {
                throw new BusinessException(502, "Binance 未返回数量规则");
            }
            return new MarketRules(
                    minimumNotional,
                    decimal(lot.getStepSize()),
                    decimal(lot.getMinQty()),
                    decimal(lot.getMaxQty()));
        } catch (ApiException exception) {
            throw translate(exception);
        }
    }

    @Override
    public OrderResult marketBuy(
            ExchangeCredentials credentials, String symbol, BigDecimal quantity, String clientOrderId) {
        try {
            NewOrderRequest request = new NewOrderRequest()
                    .symbol(symbol)
                    .side(Side.BUY)
                    .type(OrderType.MARKET)
                    .quantity(quantity.doubleValue())
                    .newClientOrderId(clientOrderId)
                    .newOrderRespType(NewOrderRespType.FULL);
            return map(authenticatedApi(credentials).newOrder(request).getData());
        } catch (ApiException exception) {
            throw translate(exception);
        }
    }

    @Override
    public Optional<OrderResult> findOrder(
            ExchangeCredentials credentials, String symbol, String clientOrderId) {
        try {
            SpotRestApi api = authenticatedApi(credentials);
            GetOrderResponse response = api.getOrder(symbol, null, clientOrderId, null).getData();
            List<MyTradesResponseInner> trades = List.of();
            if (response.getOrderId() != null && decimal(response.getExecutedQty()).signum() > 0) {
                var tradeResponse = api.myTrades(symbol, response.getOrderId(), null, null,
                        null, null, null).getData();
                trades = tradeResponse == null ? List.of() : tradeResponse;
            }
            Map<String, BigDecimal> fees = aggregateFees(trades.stream().map(trade ->
                    new Commission(trade.getCommissionAsset(), decimal(trade.getCommission()))));
            return Optional.of(map(response, fees));
        } catch (ApiException exception) {
            BinanceClientException translated = translate(exception);
            if (translated.errorCode() == -2013 || translated.httpStatus() == 404) {
                return Optional.empty();
            }
            throw translated;
        }
    }

    private SpotRestApi publicApi() {
        return new SpotRestApi(configuration(null));
    }

    private SpotRestApi authenticatedApi(ExchangeCredentials credentials) {
        return new SpotRestApi(configuration(credentials));
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

    private static OrderResult map(NewOrderResponse response) {
        List<com.binance.connector.client.spot.rest.model.NewOrderResponseFillsInner> fills =
                Optional.ofNullable(response.getFills()).orElseGet(List::of);
        Map<String, BigDecimal> fees = aggregateFees(fills.stream().map(fill ->
                new Commission(fill.getCommissionAsset(), decimal(fill.getCommission()))));
        return orderResult(
                response.getSymbol(), response.getOrderId(), response.getClientOrderId(), response.getStatus(),
                response.getExecutedQty(), response.getOrigQty(), response.getCummulativeQuoteQty(),
                response.getPrice(), fees);
    }

    private static OrderResult map(GetOrderResponse response, Map<String, BigDecimal> fees) {
        return orderResult(
                response.getSymbol(), response.getOrderId(), response.getClientOrderId(), response.getStatus(),
                response.getExecutedQty(), response.getOrigQty(), response.getCummulativeQuoteQty(),
                response.getPrice(), fees);
    }

    static OrderResult orderResult(
            String symbol,
            Long orderId,
            String clientOrderId,
            String status,
            String executedQuantity,
            String originalQuantity,
            String cumulativeQuote,
            String reportedPrice) {
        return orderResult(symbol, orderId, clientOrderId, status, executedQuantity, originalQuantity,
                cumulativeQuote, reportedPrice, Map.of());
    }

    private static OrderResult orderResult(
            String symbol,
            Long orderId,
            String clientOrderId,
            String status,
            String executedQuantity,
            String originalQuantity,
            String cumulativeQuote,
            String reportedPrice,
            Map<String, BigDecimal> fees) {
        BigDecimal executed = decimal(executedQuantity);
        BigDecimal totalCost = decimal(cumulativeQuote);
        BigDecimal averagePrice = executed.signum() > 0 && totalCost.signum() > 0
                ? totalCost.divide(executed, 18, RoundingMode.HALF_UP).stripTrailingZeros()
                : decimal(reportedPrice);
        return new OrderResult(
                symbol,
                orderId == null ? null : orderId.toString(),
                clientOrderId,
                status,
                executed,
                totalCost,
                averagePrice,
                fees);
    }

    private static Map<String, BigDecimal> aggregateFees(Stream<Commission> commissions) {
        return commissions
                .filter(item -> item.asset() != null && !item.asset().isBlank())
                .filter(item -> item.amount().signum() != 0)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        item -> item.asset().toUpperCase(), Commission::amount, BigDecimal::add));
    }

    private record Commission(String asset, BigDecimal amount) {}

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

    private static AccountBalance zeroUsdt() {
        return new AccountBalance("USDT", BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static BigDecimal decimal(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }
}
