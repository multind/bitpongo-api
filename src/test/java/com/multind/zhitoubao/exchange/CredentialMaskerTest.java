package com.multind.zhitoubao.exchange;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialMaskerTest {
    private final CredentialMasker masker = new CredentialMasker();

    @Test
    void listMaskKeepsOnlyFirstThreeCharacters() {
        assertThat(masker.maskAccessKey("abcdefghij")).isEqualTo("abc*******");
        assertThat(masker.maskAccessKey("abc")).isEqualTo("***");
    }

    @Test
    void detailMaskKeepsFirstAndLastFourWithoutLeakingMiddle() {
        assertThat(masker.maskDetail("abcdefghijklmnop")).isEqualTo("abcd********mnop");
        assertThat(masker.maskDetail("short")).isEqualTo("*****");
        assertThat(masker.isPlaceholder("abcd********mnop")).isTrue();
    }
}
