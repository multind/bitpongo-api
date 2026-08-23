package com.multind.bitpongo.notification;

import com.multind.bitpongo.common.api.BusinessException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

public final class BarkCredentialCipher {

    private static final String ENVELOPE_PREFIX = "v1:";
    private static final int NONCE_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int AES_256_KEY_LENGTH_BYTES = 32;

    private final SecretKeySpec key;
    private final SecureRandom secureRandom;

    public BarkCredentialCipher(BarkProperties properties) {
        this(properties.credentialEncryptionKey());
    }

    public BarkCredentialCipher(String base64Key) {
        this(base64Key, new SecureRandom());
    }

    BarkCredentialCipher(String base64Key, SecureRandom secureRandom) {
        this.key = decodeKey(base64Key);
        this.secureRandom = secureRandom;
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new BusinessException(400, "Bark 凭据不能为空");
        }

        byte[] nonce = new byte[NONCE_LENGTH_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = ByteBuffer.allocate(nonce.length + encrypted.length)
                    .put(nonce)
                    .put(encrypted)
                    .array();
            return ENVELOPE_PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Bark 凭据加密失败", exception);
        }
    }

    public String decrypt(String envelope) {
        if (envelope == null || !envelope.startsWith(ENVELOPE_PREFIX)) {
            throw decryptFailure();
        }

        final byte[] payload;
        try {
            payload = Base64.getDecoder().decode(envelope.substring(ENVELOPE_PREFIX.length()));
        } catch (IllegalArgumentException exception) {
            throw decryptFailure();
        }
        if (payload.length <= NONCE_LENGTH_BYTES + GCM_TAG_LENGTH_BITS / Byte.SIZE) {
            throw decryptFailure();
        }

        byte[] nonce = new byte[NONCE_LENGTH_BYTES];
        byte[] encrypted = new byte[payload.length - NONCE_LENGTH_BYTES];
        System.arraycopy(payload, 0, nonce, 0, nonce.length);
        System.arraycopy(payload, nonce.length, encrypted, 0, encrypted.length);

        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw decryptFailure();
        }
    }

    private static SecretKeySpec decodeKey(String base64Key) {
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key == null ? "" : base64Key);
        } catch (IllegalArgumentException exception) {
            throw invalidKey();
        }
        if (decoded.length != AES_256_KEY_LENGTH_BYTES) {
            throw invalidKey();
        }
        return new SecretKeySpec(decoded, "AES");
    }

    private static IllegalStateException invalidKey() {
        return new IllegalStateException("Bark 凭据加密密钥必须是 32-byte Base64");
    }

    private static BusinessException decryptFailure() {
        return new BusinessException(400, "Bark 凭据无法解密");
    }
}
