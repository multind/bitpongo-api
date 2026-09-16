package com.multind.bitpongo.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-07T04:00:00Z");
    private static final String SECRET = "compatibility-test-secret";

    @Test
    void defaultLifetimeIsThirtyDays() {
        new ApplicationContextRunner()
                .withPropertyValues("zhitoubao.jwt.secret-key=" + SECRET)
                .withUserConfiguration(JwtTokenService.class)
                .run(context -> {
                    Instant issuedAt = Instant.now();
                    String token = context.getBean(JwtTokenService.class).issue(42L);
                    JwtTokenService beforeExpiry = new JwtTokenService(SECRET, Duration.ofDays(30),
                            Clock.fixed(issuedAt.plus(Duration.ofDays(29)), ZoneOffset.UTC));
                    assertThat(beforeExpiry.decodeUserId(token)).isEqualTo(42L);
                    JwtTokenService afterExpiry = new JwtTokenService(SECRET, Duration.ofDays(30),
                            Clock.fixed(issuedAt.plus(Duration.ofDays(31)), ZoneOffset.UTC));
                    assertThatThrownBy(() -> afterExpiry.decodeUserId(token))
                            .isInstanceOf(IllegalArgumentException.class);
                });
    }

    @Test
    void issuesAndDecodesNumericUserId() {
        JwtTokenService service = serviceAt(NOW, SECRET);

        String token = service.issue(42L);

        assertThat(service.decodeUserId(token)).isEqualTo(42L);
    }

    @Test
    void rejectsWrongSignature() {
        String token = serviceAt(NOW, SECRET).issue(42L);

        assertThatThrownBy(() -> serviceAt(NOW, "another-secret").decodeUserId(token))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsExpiredToken() {
        String token = serviceAt(NOW, SECRET).issue(42L);

        assertThatThrownBy(() -> serviceAt(NOW.plus(Duration.ofMinutes(6)), SECRET).decodeUserId(token))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private JwtTokenService serviceAt(Instant instant, String secret) {
        return new JwtTokenService(secret, Duration.ofMinutes(5), Clock.fixed(instant, ZoneOffset.UTC));
    }
}
