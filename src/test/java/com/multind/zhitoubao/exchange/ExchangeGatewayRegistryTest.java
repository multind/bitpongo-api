package com.multind.zhitoubao.exchange;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ExchangeGatewayRegistryTest {
    private final ExchangeGateway binance = mock(ExchangeGateway.class);
    private final ExchangeGatewayRegistry registry = new ExchangeGatewayRegistry(binance);

    @Test
    void resolvesBinanceCaseInsensitively() {
        assertThat(registry.require("BINANCE")).isSameAs(binance);
    }

    @Test
    void keepsMultiExchangeContractWithExplicitUnsupportedError() {
        assertThatThrownBy(() -> registry.require("okx"))
                .hasMessage("不支持的交易所: okx");
    }
}
