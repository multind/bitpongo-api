package com.multind.zhitoubao.security;

import com.multind.zhitoubao.exchange.BinanceEnvironment;
import java.net.URI;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionTradingGuardTest {
    @Test
    void productionTradingIsDisabledByDefaultAndLiveUrlIsPinned() {
        BinanceEnvironment properties = new BinanceEnvironment(false,
                "https://testnet.binance.vision", "https://api.binance.com");
        assertThat(properties.liveTrading()).isFalse();
        assertThat(URI.create(properties.effectiveRestBaseUrl()).getHost())
                .isEqualTo("testnet.binance.vision");
        assertThatThrownBy(() -> new BinanceEnvironment(true,
                "https://testnet.binance.vision", "https://proxy.example.com"))
                .hasMessageContaining("生产地址");
        assertThatThrownBy(() -> new BinanceEnvironment(true,
                "https://testnet.binance.vision", "http://api.binance.com"))
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> new BinanceEnvironment(true,
                "https://testnet.binance.vision", "https://user@api.binance.com"))
                .hasMessageContaining("用户信息");
        assertThatThrownBy(() -> new BinanceEnvironment(true,
                "https://testnet.binance.vision", "https://api.binance.com:8443"))
                .hasMessageContaining("端口");
    }
}
