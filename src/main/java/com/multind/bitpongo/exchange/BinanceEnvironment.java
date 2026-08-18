package com.multind.bitpongo.exchange;

import java.net.URI;
import jakarta.annotation.PostConstruct;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record BinanceEnvironment(
        @Value("${zhitoubao.binance.live-trading:false}") boolean liveTrading,
        @Value("${zhitoubao.binance.testnet-rest-base-url:https://testnet.binance.vision}")
                String testnetRestBaseUrl,
        @Value("${zhitoubao.binance.production-rest-base-url:https://api.binance.com}")
                String productionRestBaseUrl) {

    public BinanceEnvironment {
        if (liveTrading) {
            URI production = URI.create(productionRestBaseUrl);
            if (!"https".equalsIgnoreCase(production.getScheme())) {
                throw new IllegalArgumentException("真实交易只允许使用 HTTPS");
            }
            String host = production.getHost();
            if (!"api.binance.com".equalsIgnoreCase(host)) {
                throw new IllegalArgumentException("真实交易只允许使用 Binance 生产地址");
            }
            if (production.getUserInfo() != null) {
                throw new IllegalArgumentException("真实交易地址不能包含用户信息");
            }
            if (production.getPort() != -1 && production.getPort() != 443) {
                throw new IllegalArgumentException("真实交易地址只允许 HTTPS 默认端口");
            }
        }
    }

    public String effectiveRestBaseUrl() {
        return liveTrading ? productionRestBaseUrl : testnetRestBaseUrl;
    }

    @PostConstruct
    void reportTradingMode() {
        if (liveTrading) LoggerFactory.getLogger(BinanceEnvironment.class)
                .warn("BINANCE 真实交易已显式启用，REST 地址={}", productionRestBaseUrl);
    }
}
