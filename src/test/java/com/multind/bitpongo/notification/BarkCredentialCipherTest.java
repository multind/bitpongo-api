package com.multind.bitpongo.notification;

import com.multind.bitpongo.common.api.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BarkCredentialCipherTest {

    private static final String TEST_KEY =
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    @Test
    void aesGcmUsesRandomNonceAndDetectsTampering() {
        BarkCredentialCipher cipher = new BarkCredentialCipher(TEST_KEY);

        String first = cipher.encrypt("fake-device-key");
        String second = cipher.encrypt("fake-device-key");

        assertThat(first).startsWith("v1:").isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("fake-device-key");
        assertThatThrownBy(() -> cipher.decrypt(tamper(first)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsInvalidEncryptionKeys() {
        assertThatThrownBy(() -> new BarkCredentialCipher("not-base64"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new BarkCredentialCipher(
                Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMalformedOrUnsupportedCiphertext() {
        BarkCredentialCipher cipher = new BarkCredentialCipher(TEST_KEY);

        assertThatThrownBy(() -> cipher.decrypt("v2:ZmFrZQ=="))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> cipher.decrypt("v1:not-base64"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> cipher.decrypt("v1:AA=="))
                .isInstanceOf(BusinessException.class);
    }

    private static String tamper(String envelope) {
        byte[] payload = Base64.getDecoder().decode(envelope.substring(3));
        payload[payload.length - 1] ^= 1;
        return "v1:" + Base64.getEncoder().encodeToString(payload);
    }
}
