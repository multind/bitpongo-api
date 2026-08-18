package com.multind.bitpongo.market;

import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class SymbolNormalizer {
    public String toBinance(String symbol) {
        String normalized = normalize(symbol).replace("/", "");
        if (!normalized.endsWith("USDT")) normalized += "USDT";
        if (normalized.length() <= 4) throw new IllegalArgumentException("币种格式错误");
        return normalized;
    }

    public String toInternal(String symbol) {
        String normalized = normalize(symbol).replace("/", "");
        if (!normalized.endsWith("USDT") || normalized.length() <= 4) {
            throw new IllegalArgumentException("仅支持 USDT 交易对");
        }
        return normalized.substring(0, normalized.length() - 4) + "/USDT";
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("币种不能为空");
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
