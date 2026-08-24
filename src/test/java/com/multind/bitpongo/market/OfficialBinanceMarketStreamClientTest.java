package com.multind.bitpongo.market;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OfficialBinanceMarketStreamClientTest {
    @Test
    void extractsOnlyThePathFromTheConfiguredStreamUrl() {
        assertThat(
                OfficialBinanceMarketStreamClient.normalizeStreamPath("wss://stream.binance.com:9443"))
                .isEqualTo("");
        assertThat(
                OfficialBinanceMarketStreamClient.normalizeStreamPath("wss://stream.binance.com:9443/ws"))
                .isEqualTo("/ws");
    }

    @Test
    void appliesTheConfiguredTextMessageLimitToTheBinanceConnector() {
        var client = new OfficialBinanceMarketStreamClient(
                "wss://stream.binance.com:9443", 1_048_576L);

        assertThat(client.createClientConfiguration().getMessageMaxSize())
                .isEqualTo(1_048_576L);
    }
}
