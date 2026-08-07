package com.multind.zhitoubao.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService {

    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();
    private static final String HEADER = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final byte[] secret;
    private final Duration lifetime;
    private final Clock clock;

    @Autowired
    public JwtTokenService(
            @Value("${zhitoubao.jwt.secret-key:}") String secret,
            @Value("${zhitoubao.jwt.access-token-expire-minutes:300}") long expireMinutes) {
        this(secret, Duration.ofMinutes(expireMinutes), Clock.systemUTC());
    }

    public JwtTokenService(String secret, Duration lifetime, Clock clock) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET_KEY 未配置");
        }
        if (lifetime == null || lifetime.isZero() || lifetime.isNegative()) {
            throw new IllegalArgumentException("JWT 有效期必须大于 0");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.lifetime = lifetime;
        this.clock = clock;
    }

    public String issue(long userId) {
        long expiresAt = clock.instant().plus(lifetime).getEpochSecond();
        try {
            String payload = encode(JSON.writeValueAsBytes(Map.of("id", userId, "exp", expiresAt)));
            String content = HEADER + "." + payload;
            return content + "." + BASE64_URL_ENCODER.encodeToString(sign(content));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("JWT 编码失败", exception);
        }
    }

    public long decodeUserId(String token) {
        try {
            String[] parts = token == null ? new String[0] : token.split("\\.", -1);
            if (parts.length != 3) {
                throw new IllegalArgumentException("Token 格式无效");
            }
            String content = parts[0] + "." + parts[1];
            byte[] suppliedSignature = BASE64_URL_DECODER.decode(parts[2]);
            if (!MessageDigest.isEqual(sign(content), suppliedSignature)) {
                throw new IllegalArgumentException("Token 签名无效");
            }

            JsonNode payload = JSON.readTree(BASE64_URL_DECODER.decode(parts[1]));
            if (!payload.has("id") || !payload.get("id").canConvertToLong() || !payload.has("exp")) {
                throw new IllegalArgumentException("Token 声明无效");
            }
            long expiresAt = payload.get("exp").asLong();
            if (expiresAt <= clock.instant().getEpochSecond()) {
                throw new IllegalArgumentException("Token 已过期");
            }
            return payload.get("id").asLong();
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Token 无效", exception);
        }
    }

    private byte[] sign(String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(content.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 不可用", exception);
        }
    }

    private static String encode(String value) {
        return encode(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(byte[] value) {
        return BASE64_URL_ENCODER.encodeToString(value);
    }
}
