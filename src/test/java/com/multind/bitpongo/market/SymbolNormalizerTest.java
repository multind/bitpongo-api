package com.multind.bitpongo.market;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SymbolNormalizerTest {
    private final SymbolNormalizer symbols = new SymbolNormalizer();

    @Test
    void convertsBinanceAndInternalSymbols() {
        assertThat(symbols.toInternal("BTCUSDT")).isEqualTo("BTC/USDT");
        assertThat(symbols.toBinance("btc/usdt")).isEqualTo("BTCUSDT");
        assertThat(symbols.toBinance("ETH")).isEqualTo("ETHUSDT");
    }

    @Test
    void rejectsEmptyAndNonUsdtSymbols() {
        assertThatThrownBy(() -> symbols.toInternal("BTCUSD")).hasMessageContaining("USDT");
        assertThatThrownBy(() -> symbols.toBinance(" ")).hasMessageContaining("币种");
    }
}
