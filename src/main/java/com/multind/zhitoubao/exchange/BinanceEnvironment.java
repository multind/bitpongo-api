package com.multind.zhitoubao.exchange;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record BinanceEnvironment(
        @Value("${zhitoubao.binance.live-trading:false}") boolean liveTrading,
        @Value("${zhitoubao.binance.testnet-rest-base-url:https://testnet.binance.vision}")
                String testnetRestBaseUrl,
        @Value("${zhitoubao.binance.production-rest-base-url:https://api.binance.com}")
                String productionRestBaseUrl) {

    public String effectiveRestBaseUrl() {
        return liveTrading ? productionRestBaseUrl : testnetRestBaseUrl;
    }
}
