package com.multind.bitpongo.exchange;

import com.multind.bitpongo.notification.BarkCredentialCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts exchange API secrets at rest (AES-256-GCM, same envelope as Bark credentials).
 *
 * <p>Key: {@code EXCHANGE_CREDENTIAL_ENCRYPTION_KEY}; when unset it falls back to
 * {@code BARK_CREDENTIAL_ENCRYPTION_KEY} so existing deployments keep starting. Values written before
 * encryption existed are plaintext and are still readable; {@link ExchangeCredentialBackfill} encrypts
 * them at startup.</p>
 *
 * <p>JPA instantiates {@link ExchangeSecretConverter} itself, so the active cipher is published through a
 * static holder set when this bean is created.</p>
 */
@Component
public class ExchangeCredentialCrypto {
    static final String PREFIX = "v1:";
    private static final Logger log = LoggerFactory.getLogger(ExchangeCredentialCrypto.class);
    private static volatile BarkCredentialCipher active;

    public ExchangeCredentialCrypto(
            @Value("${zhitoubao.exchange.credential-encryption-key:}") String exchangeKey,
            @Value("${zhitoubao.notifications.bark.credential-encryption-key:}") String barkKey) {
        String key = exchangeKey != null && !exchangeKey.isBlank() ? exchangeKey : barkKey;
        if (key == null || key.isBlank()) {
            log.warn("未配置交易所凭据加密密钥，交易所 Secret 将以明文保存");
            active = null;
        } else {
            active = new BarkCredentialCipher(key);
        }
    }

    public boolean enabled() {
        return active != null;
    }

    static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    static String encrypt(String value) {
        BarkCredentialCipher cipher = active;
        if (value == null || value.isEmpty() || cipher == null || isEncrypted(value)) return value;
        return cipher.encrypt(value);
    }

    static String decrypt(String value) {
        if (!isEncrypted(value)) return value;
        BarkCredentialCipher cipher = active;
        if (cipher == null) {
            throw new IllegalStateException("交易所凭据已加密，但未配置解密密钥");
        }
        return cipher.decrypt(value);
    }
}
