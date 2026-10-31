package com.multind.bitpongo.exchange;

import com.multind.bitpongo.common.api.BusinessException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class FuturesGatewayRegistry {
    private final FuturesGateway binance;

    public FuturesGatewayRegistry(@Qualifier("binanceFuturesGateway") FuturesGateway binance) {
        this.binance = binance;
    }

    public FuturesGateway require(String code) {
        if (code != null && "binance".equalsIgnoreCase(code.trim())) return binance;
        throw new BusinessException(400, "暂不支持该合约交易所");
    }
}