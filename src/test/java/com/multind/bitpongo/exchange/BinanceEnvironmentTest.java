package com.multind.bitpongo.exchange;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceEnvironmentTest {
    @Test
    void defaultsCanOnlySelectTestnetUntilLiveGateIsExplicit() {
        BinanceEnvironment testnet = new BinanceEnvironment(
                false, "https://testnet.binance.vision", "https://api.binance.com");
        BinanceEnvironment live = new BinanceEnvironment(
                true, "https://testnet.binance.vision", "https://api.binance.com");

        assertThat(testnet.effectiveRestBaseUrl()).isEqualTo("https://testnet.binance.vision");
        assertThat(live.effectiveRestBaseUrl()).isEqualTo("https://api.binance.com");
    }
}
