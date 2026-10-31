package com.multind.bitpongo.exchange;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** USDT-M futures endpoint selection; it follows the same live-trading switch as spot. */
@Component
public record BinanceFuturesEnvironment(
        BinanceEnvironment binance,
        @Value("${zhitoubao.binance.futures-testnet-rest-base-url:https://testnet.binancefuture.com}")
                String testnetRestBaseUrl,
        @Value("${zhitoubao.binance.futures-production-rest-base-url:https://fapi.binance.com}")
                String productionRestBaseUrl) {

    public BinanceFuturesEnvironment {
        if (binance != null && binance.liveTrading()) {
            URI production = URI.create(productionRestBaseUrl);
            if (!"https".equalsIgnoreCase(production.getScheme())) {
                throw new IllegalArgumentException("合约真实交易只允许使用 HTTPS");
            }
            if (!"fapi.binance.com".equalsIgnoreCase(production.getHost())) {
                throw new IllegalArgumentException("合约真实交易只允许使用 Binance 生产地址");
            }
            if (production.getUserInfo() != null) {
                throw new IllegalArgumentException("合约真实交易地址不能包含用户信息");
            }
            if (production.getPort() != -1 && production.getPort() != 443) {
                throw new IllegalArgumentException("合约真实交易地址只允许 HTTPS 默认端口");
            }
        }
    }

    public String effectiveRestBaseUrl() {
        return binance.liveTrading() ? productionRestBaseUrl : testnetRestBaseUrl;
    }
}