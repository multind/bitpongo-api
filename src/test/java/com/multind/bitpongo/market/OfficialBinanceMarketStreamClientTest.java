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
}
