package com.multind.zhitoubao.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.HexFormat;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Service;

@Service
public class PasswordCompatibilityService {

    private static final int ITERATIONS = 100_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final HexFormat HEX = HexFormat.of();
    private final SecureRandom secureRandom = new SecureRandom();

    public boolean matches(String plainPassword, String storedPassword) {
        if (plainPassword == null || storedPassword == null
                || !storedPassword.matches("[0-9a-fA-F]{96}")) {
            return false;
        }
        String salt = storedPassword.substring(0, 32);
        byte[] expected = HEX.parseHex(storedPassword.substring(32));
        byte[] actual = derive(plainPassword, salt);
        return MessageDigest.isEqual(expected, actual);
    }

    public String hash(String password) {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        byte[] saltBytes = new byte[SALT_BYTES];
        secureRandom.nextBytes(saltBytes);
        String salt = HEX.formatHex(saltBytes);
        return salt + HEX.formatHex(derive(password, salt));
    }

    private byte[] derive(String password, String salt) {
        PBEKeySpec spec = new PBEKeySpec(
                password.toCharArray(),
                salt.getBytes(StandardCharsets.US_ASCII),
                ITERATIONS,
                KEY_LENGTH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException exception) {
            throw new IllegalStateException("PBKDF2 不可用", exception);
        } finally {
            spec.clearPassword();
        }
    }
}
