package com.multind.bitpongo.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class ExchangeSecretConverterTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void encryptsOnWriteAndDecryptsOnRead() {
        new ExchangeCredentialCrypto(KEY, "");
        ExchangeSecretConverter converter = new ExchangeSecretConverter();
        String stored = converter.convertToDatabaseColumn("plain-secret-value");
        assertThat(stored).startsWith("v1:").doesNotContain("plain-secret-value");
        assertThat(converter.convertToEntityAttribute(stored)).isEqualTo("plain-secret-value");
    }

    @Test
    void legacyPlaintextIsStillReadableAndNullsPassThrough() {
        new ExchangeCredentialCrypto("", KEY);
        ExchangeSecretConverter converter = new ExchangeSecretConverter();
        assertThat(converter.convertToEntityAttribute("legacyPlainSecret")).isEqualTo("legacyPlainSecret");
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void alreadyEncryptedValuesAreNotEncryptedTwice() {
        new ExchangeCredentialCrypto(KEY, "");
        ExchangeSecretConverter converter = new ExchangeSecretConverter();
        String stored = converter.convertToDatabaseColumn("secret");
        assertThat(converter.convertToDatabaseColumn(stored)).isEqualTo(stored);
    }
}
