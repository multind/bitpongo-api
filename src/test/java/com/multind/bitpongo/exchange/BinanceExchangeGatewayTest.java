package com.multind.bitpongo.exchange;

import com.multind.bitpongo.common.api.BusinessException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BinanceExchangeGatewayTest {
    private final BinanceSpotClient client = mock(BinanceSpotClient.class);
    private final BinanceExchangeGateway gateway = new BinanceExchangeGateway(client);
    private final ExchangeCredentials credentials = new ExchangeCredentials("key", "secret", null);

    @Test
    void delegatesAccountRulesAndOrdersThroughDomainPort() {
        AccountBalance balance = new AccountBalance("USDT", bd("12.3"), bd("0"));
        MarketRules rules = new MarketRules(bd("10"), bd("0.001"), bd("0.001"), bd("100"));
        OrderResult order = new OrderResult("BTCUSDT", "12", "client-1", "FILLED",
                bd("0.1"), bd("6000"), bd("60000"), Map.of());
        when(client.account(credentials)).thenReturn(balance);
        when(client.marketRules("BTCUSDT")).thenReturn(rules);
        when(client.marketBuy(credentials, "BTCUSDT", bd("0.1"), "client-1")).thenReturn(order);
        when(client.findOrder(credentials, "BTCUSDT", "client-1")).thenReturn(Optional.of(order));

        assertThat(gateway.verifyCredentials(credentials)).isEqualTo(balance);
        assertThat(gateway.getMarketRules("btcusdt")).isEqualTo(rules);
        assertThat(gateway.marketBuy(credentials, "btcusdt", bd("0.1"), "client-1")).isEqualTo(order);
        assertThat(gateway.findOrder(credentials, "btcusdt", "client-1")).contains(order);
    }

    @Test
    void mapsAuthenticationAndRateLimitFailures() {
        when(client.account(credentials)).thenThrow(new BinanceClientException(401, -2015, "bad key", false));
        when(client.findOrder(credentials, "BTCUSDT", "client-1"))
                .thenThrow(new BinanceClientException(429, -1003, "rate limit", false));

        assertThatThrownBy(() -> gateway.verifyCredentials(credentials))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("API密钥认证失败，请检查密钥是否正确");
                    assertThat(e.getCode()).isEqualTo(400);
                });
        assertThatThrownBy(() -> gateway.findOrder(credentials, "BTCUSDT", "client-1"))
                .isInstanceOf(RetryableExchangeException.class);
    }

    @Test
    void timeoutLeavesOrderResultAmbiguousWithoutRetrying() {
        when(client.marketBuy(credentials, "BTCUSDT", bd("0.1"), "client-1"))
                .thenThrow(new BinanceClientException(0, 0, "timeout", true));

        assertThatThrownBy(() -> gateway.marketBuy(credentials, "BTCUSDT", bd("0.1"), "client-1"))
                .isInstanceOf(AmbiguousOrderException.class)
                .hasMessageContaining("结果不明确");
        verify(client).marketBuy(credentials, "BTCUSDT", bd("0.1"), "client-1");
    }

    @Test
    void serverErrorDuringSubmissionIsAlsoAmbiguous() {
        when(client.marketBuy(credentials, "BTCUSDT", bd("0.1"), "client-1"))
                .thenThrow(new BinanceClientException(503, 0, "gateway failure", false));

        assertThatThrownBy(() -> gateway.marketBuy(credentials, "BTCUSDT", bd("0.1"), "client-1"))
                .isInstanceOf(AmbiguousOrderException.class);
    }

    @Test
    void binanceUnknownExecutionCodesAreAmbiguous() {
        when(client.marketBuy(credentials, "BTCUSDT", bd("0.1"), "client-1"))
                .thenThrow(new BinanceClientException(400, -1006, "unexpected response", false));

        assertThatThrownBy(() -> gateway.marketBuy(credentials, "BTCUSDT", bd("0.1"), "client-1"))
                .isInstanceOf(AmbiguousOrderException.class);
    }

    private static BigDecimal bd(String value) { return new BigDecimal(value); }
}
