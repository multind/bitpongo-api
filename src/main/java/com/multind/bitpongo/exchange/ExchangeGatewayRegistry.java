package com.multind.bitpongo.exchange;

import com.multind.bitpongo.common.api.BusinessException;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class ExchangeGatewayRegistry {
    private final ExchangeGateway binance;

    public ExchangeGatewayRegistry(@Qualifier("binanceExchangeGateway") ExchangeGateway binance) {
        this.binance = binance;
    }

    public ExchangeGateway require(String code) {
        String normalized = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
        if ("binance".equals(normalized)) {
            return binance;
        }
        throw new BusinessException(400, "不支持的交易所: " + normalized);
    }
}
